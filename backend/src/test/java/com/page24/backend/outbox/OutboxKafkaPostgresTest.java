package com.page24.backend.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.OrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole application in kafka mode, with its real scheduling and after-commit wake-up, against a real PostgreSQL
 * and a real Kafka: what an order leaves in the outbox and on the topic, including while Kafka is down. Needs Docker;
 * skipped without it (CI fails if a *PostgresTest is skipped).
 */
// @Testcontainers first, so that the application context is closed before the containers are stopped
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "careplan.queue.type=kafka",
        "spring.main.lazy-initialization=false",
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxKafkaPostgresTest {

    private static final String TOPIC = "careplan.requested";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    // A fixed host port: a stopped and restarted container must come back at the address the application knows.
    @Container
    private static final KafkaContainer KAFKA = onFixedPort(new KafkaContainer("apache/kafka:4.1.1"));

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private CarePlanRepository carePlanRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("a) a rolled-back order leaves no outbox row")
    void rolledBackOrderLeavesNoOutboxRow() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<Long> carePlanIds = new ArrayList<>();

        transaction.executeWithoutResult(status -> {
            long carePlanId = carePlanIdOf(orderService.createOrder(newOrder()));
            carePlanIds.add(carePlanId);
            // the row is there inside the transaction ...
            assertThat(outboxRows(carePlanId)).isEqualTo(1);
            status.setRollbackOnly();
        });

        // ... and gone with it
        assertThat(outboxRows(carePlanIds.get(0))).isZero();
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("b) a committed order appears on careplan.requested, keyed by carePlanId, and is marked sent")
    void committedOrderIsSentToTheRequestTopic() {
        long carePlanId = carePlanIdOf(orderService.createOrder(newOrder()));

        await().atMost(Duration.ofSeconds(30)).until(() -> isSent(carePlanId));

        List<ConsumerRecord<String, String>> records = recordsWithKey(carePlanId);
        assertThat(records).hasSize(1);
        JsonNode event = jsonMapper.readTree(records.get(0).value());
        assertThat(event.get("carePlanId").longValue()).isEqualTo(carePlanId);
        assertThat(event.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(event.get("eventId").stringValue()).isEqualTo(jdbc.queryForObject(
                "SELECT event_id::text FROM outbox_event WHERE aggregate_id = ?", String.class, carePlanId));
        assertThat(records.get(0).headers()).isEmpty(); // no Java type headers
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("c) Kafka stopped: orders still succeed and wait in the outbox; after the restart each is sent exactly once")
    void ordersWaitWhileKafkaIsDownAndAreSentOnceAfterwards() throws Exception {
        KAFKA.getDockerClient().stopContainerCmd(KAFKA.getContainerId()).exec();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
        List<Long> carePlanIds = new ArrayList<>();
        try {
            // When: two orders are placed while the broker is down
            long started = System.nanoTime();
            for (int i = 0; i < 2; i++) {
                carePlanIds.add(carePlanIdOf(orderService.createOrder(newOrder())));
            }
            // ordering does not wait for Kafka
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));

            // Then: the rows pile up, the sender keeps trying round after round, and the plans are PENDING
            await().atMost(Duration.ofSeconds(150)).until(() -> failedAttempts(carePlanIds) >= 8);
            for (long carePlanId : carePlanIds) {
                assertThat(isSent(carePlanId)).isFalse();
                assertThat(carePlanRepository.findById(carePlanId).orElseThrow().getStatus())
                        .isEqualTo(CarePlan.Status.PENDING);
            }
        } finally {
            root.detachAppender(logs);
            KAFKA.getDockerClient().startContainerCmd(KAFKA.getContainerId()).exec();
        }

        // eight failed rounds did not fill the log: no stack traces, one warning from the sender, and only a handful
        // of lines from the Kafka client (without the logging.level settings it writes more than ten a second)
        List<ILoggingEvent> visible = logs.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .toList();
        assertThat(visible).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        assertThat(visible).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN)
                        && event.getLoggerName().startsWith("com.page24.backend.outbox"))
                .hasSizeLessThanOrEqualTo(1);
        assertThat(visible).filteredOn(event -> event.getLoggerName().startsWith("org.apache.kafka")
                        || event.getLoggerName().startsWith("org.springframework.kafka"))
                .hasSizeLessThan(25);

        // And after the restart everything is sent, each message exactly once
        await().atMost(Duration.ofSeconds(120))
                .until(() -> carePlanIds.stream().allMatch(this::isSent));
        // give any late duplicate the chance to appear: the producer has given up on every earlier attempt by now
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30))
                .until(() -> carePlanIds.stream().allMatch(id -> recordsWithKey(id).size() == 1));
        // the consumer reconnects to the restarted broker by itself and generates both plans
        await().atMost(Duration.ofSeconds(120)).until(() -> carePlanIds.stream().allMatch(id ->
                carePlanRepository.findById(id).orElseThrow().getStatus() == CarePlan.Status.COMPLETED));
    }

    private static CreateOrderRequest newOrder() {
        int n = SEQUENCE.incrementAndGet();
        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName("Outbox");
        request.setPatientLastName("Patient" + n);
        request.setPatientMrn(String.format("%06d", 800_000 + n));
        request.setPatientDateOfBirth(LocalDate.of(1980, 1, 1));
        request.setProviderName("Dr Outbox" + n);
        request.setProviderNpi(String.format("%010d", 1_800_000_000L + n));
        request.setMedicationName("IVIG");
        request.setPrimaryDiagnosis("G70.00");
        return request;
    }

    private long carePlanIdOf(OrderResponse order) {
        return carePlanRepository.findByOrderId(order.getId()).orElseThrow().getId();
    }

    private int outboxRows(long carePlanId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, carePlanId);
    }

    private boolean isSent(long carePlanId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND sent_at IS NOT NULL",
                Integer.class, carePlanId) == 1;
    }

    private int failedAttempts(List<Long> carePlanIds) {
        return carePlanIds.stream()
                .mapToInt(id -> jdbc.queryForObject(
                        "SELECT attempts FROM outbox_event WHERE aggregate_id = ?", Integer.class, id))
                .sum();
    }

    private List<ConsumerRecord<String, String>> recordsWithKey(long carePlanId) {
        return KafkaTestReader.readAll(KAFKA.getBootstrapServers(), TOPIC).stream()
                .filter(record -> record.key().equals(String.valueOf(carePlanId)))
                .toList();
    }

    private static KafkaContainer onFixedPort(KafkaContainer container) {
        try (ServerSocket socket = new ServerSocket(0)) {
            container.setPortBindings(List.of(socket.getLocalPort() + ":9092"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return container;
    }
}
