package com.page24.backend.config;

import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.outbox.OutboxCleanup;
import com.page24.backend.outbox.OutboxRelay;
import com.page24.backend.outbox.OutboxWakeUpListener;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.CarePlanKafkaConsumer;
import com.page24.backend.service.CarePlanSweeper;
import com.page24.backend.service.CarePlanQueue;
import com.page24.backend.service.CarePlanWorker;
import com.page24.backend.service.KafkaCarePlanQueue;
import com.page24.backend.service.OrderService;
import com.page24.backend.service.QueueService;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole application in kafka mode against a real broker and a real PostgreSQL (Flyway creates the outbox),
 * started eagerly as in production, so a missing bean would fail the start. Needs Docker; skipped without it (CI
 * fails if a *PostgresTest is skipped).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "careplan.queue.type=kafka",
        "spring.main.lazy-initialization=false",
        // the test profile excludes Redis auto-configuration; the real application has it, so allow it here
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QueueTypeKafkaStartupPostgresTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
    private ApplicationContext context;

    @Autowired
    private OrderService orderService;

    @Autowired
    private CarePlanRepository carePlanRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProducerFactory<String, String> producerFactory;

    @Test
    @DisplayName("kafka mode creates the request and dead-letter topics with 3 partitions and 1 replica, and no others")
    void createsExactlyTheTwoTopics() throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Set<String> topics = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            assertThat(topics).containsExactlyInAnyOrder("careplan.requested", "careplan.requested.dlt");

            Map<String, TopicDescription> described =
                    admin.describeTopics(topics).allTopicNames().get(30, TimeUnit.SECONDS);
            assertThat(described.values()).allSatisfy(topic -> {
                assertThat(topic.partitions()).hasSize(3);
                assertThat(topic.partitions()).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
            });
        }
    }

    @Test
    @DisplayName("kafka mode replaces the Redis queue and worker with the outbox queue, its sender and consumer")
    void usesTheOutboxInsteadOfTheRedisQueueAndWorker() {
        assertThat(context.getBean(CarePlanQueue.class)).isInstanceOf(KafkaCarePlanQueue.class);
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(OutboxWakeUpListener.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(OutboxCleanup.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(CarePlanKafkaConsumer.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(CarePlanSweeper.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(QueueService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CarePlanWorker.class)).isEmpty();
    }

    @Test
    @DisplayName("the producer really runs with acks=all, idempotence and the timeouts of application.properties")
    void producerIsConfiguredForSafeSending() {
        assertThat(producerFactory.getConfigurationProperties())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "8000")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("kafka mode: an order is written to the outbox and its care plan gets generated")
    void orderIsQueuedInTheOutboxAndGenerated() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName("Kafka");
        request.setPatientLastName("Startup");
        request.setPatientMrn("900001");
        request.setPatientDateOfBirth(LocalDate.of(1980, 1, 1));
        request.setProviderName("Dr Topic");
        request.setProviderNpi("1900000001");
        request.setMedicationName("IVIG");
        request.setPrimaryDiagnosis("G70.00");

        OrderResponse order = orderService.createOrder(request);

        CarePlan carePlan = carePlanRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, carePlan.getId()))
                .isEqualTo(1);
        await().atMost(Duration.ofSeconds(60)).until(() -> carePlanRepository.findById(carePlan.getId())
                .orElseThrow().getStatus() == CarePlan.Status.COMPLETED);
    }
}
