package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.outbox.CarePlanRequestedEvent;
import com.page24.backend.outbox.KafkaTestReader;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.QueueTestSupport.CarePlanRow;
import com.page24.backend.service.QueueTestSupport.Database;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The whole application in kafka mode (order, outbox, Kafka, consumer, local mock LLM) against a real PostgreSQL and
 * a real Kafka. One consumer thread, so that "an order waiting behind another" can be arranged. More failure
 * scenarios belong to CP-C6. Needs Docker; skipped without it (CI fails if a *PostgresTest is skipped).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "careplan.queue.type=kafka",
        "spring.kafka.listener.concurrency=1",
        "careplan.sweeper.interval=1h",
        "spring.main.lazy-initialization=false",
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CarePlanConsumerPostgresTest {

    private static final String TOPIC = "careplan.requested";
    private static final String DEAD_LETTERS = "careplan.requested.dlt";
    private static final String HOLD = "HoldMe";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    // A fixed host port: the database is stopped and started again in one test.
    @Container
    private static final PostgreSQLContainer POSTGRES = onFixedPort(new PostgreSQLContainer("postgres:16-alpine"));

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

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
    private CarePlanRepository carePlans;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @MockitoSpyBean
    private LocalLLM llm;

    @MockitoSpyBean
    private CarePlanEventParser parser;

    @MockitoSpyBean
    private CarePlanProcessor processor;

    private final Database database = new Database(POSTGRES);
    private final CountDownLatch insideTheLlm = new CountDownLatch(1);
    private final CountDownLatch releaseTheLlm = new CountDownLatch(1);

    @AfterEach
    void neverLeaveTheLlmHeld() {
        releaseTheLlm.countDown();
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("order -> outbox -> Kafka -> consumer -> COMPLETED with the mock plan, offset committed")
    void orderIsGeneratedThroughKafka() {
        long carePlanId = placeOrder("Wiring");

        awaitStatus(carePlanId, "COMPLETED", Duration.ofSeconds(60));

        CarePlanRow row = database.find(carePlanId).orElseThrow();
        assertThat(row.content()).contains("[LOCAL MOCK CARE PLAN]");
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.startedAt()).isNotNull();
        assertThat(row.finishedAt()).isNotNull();
        assertThat(claimToken(carePlanId)).isEqualTo(1);
        // nothing is left uncommitted for the consumer group
        await().atMost(Duration.ofSeconds(30)).until(() -> consumerLag() == 0);
    }

    @Test
    @DisplayName("a message that cannot be read goes to the dead letter at once, without retries, and blocks nothing")
    @WithMockUser(roles = "ASSISTANT")
    void unreadableMessageIsDeadLetteredWithoutRetry() throws Exception {
        String garbage = "Jane Doe, MRN 123456, this is not an event";

        kafka.send(TOPIC, "bad", garbage).get(10, TimeUnit.SECONDS);
        long next = placeOrder("AfterGarbage");

        await().atMost(Duration.ofSeconds(20)).until(() -> deadLetters("bad").size() == 1);
        ConsumerRecord<String, String> dead = deadLetters("bad").get(0);
        assertThat(dead.value()).isEqualTo(garbage);
        assertNoExceptionTextInHeaders(dead);
        // read once by the listener and once by the recoverer; a retried message would have been read many times
        verify(parser, times(2)).parse(garbage);
        awaitStatus(next, "COMPLETED", Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("claim limit: the plan is FAILED and its message dead-lettered after one delivery, no LLM call")
    @WithMockUser(roles = "ASSISTANT")
    void claimLimitIsDeadLetteredWithoutRetry() throws Exception {
        long carePlanId = placeOrder("Limit");
        awaitStatus(carePlanId, "COMPLETED", Duration.ofSeconds(60));
        // as if three consumers in a row had claimed it and died
        jdbc.update("UPDATE care_plans SET status = 'PROCESSING', reclaim_count = 2, "
                + "lease_expires_at = now() - INTERVAL '1 second' WHERE id = ?", carePlanId);
        UUID eventId = UUID.randomUUID();
        String key = "limit-" + carePlanId;

        kafka.send(TOPIC, key, JsonMapper.builder().build().writeValueAsString(
                CarePlanRequestedEvent.of(eventId, Instant.now(), carePlanId))).get(10, TimeUnit.SECONDS);

        awaitStatus(carePlanId, "FAILED", Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(20)).until(() -> deadLetters(key).size() == 1);
        verify(processor, times(1)).process(eventId, carePlanId);
        assertThat(database.find(carePlanId).orElseThrow().errorMessage())
                .isEqualTo("Generation could not be completed; please regenerate");
        assertNoExceptionTextInHeaders(deadLetters(key).get(0));
    }

    @Test
    @DisplayName("database stopped for 60 s: the order being generated and the one waiting both end COMPLETED, no dead letter")
    @WithMockUser(roles = "ASSISTANT")
    void databaseOutageOfOneMinuteLosesNothing() throws Exception {
        doAnswer(invocation -> {
            if (invocation.<String>getArgument(0).contains(HOLD)) {
                insideTheLlm.countDown();
                assertThat(releaseTheLlm.await(90, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(llm).generateCarePlan(anyString());

        // Given: one order inside the LLM call and one whose message waits behind it in Kafka
        long inProgress = placeOrder(HOLD);
        assertThat(insideTheLlm.await(60, TimeUnit.SECONDS)).isTrue();
        long waiting = placeOrder("Waiting");
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND sent_at IS NOT NULL",
                Integer.class, waiting) == 1);
        assertThat(database.find(inProgress).orElseThrow().status()).isEqualTo("PROCESSING");
        assertThat(database.find(waiting).orElseThrow().status()).isEqualTo("PENDING");

        // When: the database is gone for a minute, and the LLM answers during that time
        POSTGRES.getDockerClient().stopContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            releaseTheLlm.countDown();
            await().pollDelay(Duration.ofSeconds(60)).atMost(Duration.ofSeconds(70)).until(() -> true);
        } finally {
            POSTGRES.getDockerClient().startContainerCmd(POSTGRES.getContainerId()).exec();
        }

        // Then: both end COMPLETED
        await().atMost(Duration.ofSeconds(240)).ignoreExceptions()
                .until(() -> "COMPLETED".equals(database.find(inProgress).orElseThrow().status())
                        && "COMPLETED".equals(database.find(waiting).orElseThrow().status()));
        // the one being generated was taken over by its own message and generated again; the waiting one was not
        assertThat(claimToken(inProgress)).isEqualTo(2);
        assertThat(claimToken(waiting)).isEqualTo(1);
        assertThat(deadLetters(String.valueOf(inProgress))).isEmpty();
        assertThat(deadLetters(String.valueOf(waiting))).isEmpty();
    }

    private long placeOrder(String lastName) {
        int n = SEQUENCE.incrementAndGet();
        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName("Consumer");
        request.setPatientLastName(lastName);
        request.setPatientMrn(String.format("%06d", 700_000 + n));
        request.setPatientDateOfBirth(LocalDate.of(1980, 1, 1));
        request.setProviderName("Dr Consumer" + n);
        request.setProviderNpi(String.format("%010d", 1_700_000_000L + n));
        request.setMedicationName("IVIG");
        request.setPrimaryDiagnosis("G70.00");
        OrderResponse order = orderService.createOrder(request);
        return carePlans.findByOrderId(order.getId()).orElseThrow().getId();
    }

    private void awaitStatus(long carePlanId, String status, Duration atMost) {
        await().atMost(atMost).until(() -> status.equals(database.find(carePlanId).orElseThrow().status()));
    }

    private int claimToken(long carePlanId) {
        return jdbc.queryForObject("SELECT claim_token FROM care_plans WHERE id = ?", Integer.class, carePlanId);
    }

    private static List<ConsumerRecord<String, String>> deadLetters(String key) {
        return KafkaTestReader.readAll(KAFKA.getBootstrapServers(), DEAD_LETTERS).stream()
                .filter(record -> record.key().equals(key))
                .toList();
    }

    /** No exception message, no stack trace and nothing about a patient in any header. */
    private static void assertNoExceptionTextInHeaders(ConsumerRecord<String, String> record) {
        List<Header> headers = StreamSupport.stream(record.headers().spliterator(), false).toList();
        assertThat(headers).extracting(Header::key)
                .contains(KafkaHeaders.DLT_ORIGINAL_TOPIC, KafkaHeaders.DLT_ORIGINAL_OFFSET)
                .doesNotContain(KafkaHeaders.DLT_EXCEPTION_MESSAGE, KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
        String values = headers.stream()
                .map(header -> new String(header.value(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("\n"));
        assertThat(values).doesNotContain("Jane Doe").doesNotContain("123456").doesNotContain("\tat ");
    }

    /** Records on careplan.requested that the consumer group has not committed yet. */
    private static long consumerLag() throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets("careplan-generator")
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = List.of(0, 1, 2).stream()
                    .collect(Collectors.toMap(p -> new TopicPartition(TOPIC, p), p -> OffsetSpec.latest()));
            return admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS).entrySet().stream()
                    .mapToLong(end -> end.getValue().offset()
                            - (committed.get(end.getKey()) == null ? 0 : committed.get(end.getKey()).offset()))
                    .sum();
        }
    }

    private static PostgreSQLContainer onFixedPort(PostgreSQLContainer container) {
        try (ServerSocket socket = new ServerSocket(0)) {
            container.setPortBindings(List.of(socket.getLocalPort() + ":5432"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return container;
    }
}
