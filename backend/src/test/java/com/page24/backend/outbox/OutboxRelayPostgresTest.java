package com.page24.backend.outbox;

import com.page24.backend.config.KafkaProducerConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sender against a real PostgreSQL (row locks, SKIP LOCKED, the V4 table from Flyway) and a real Kafka, with the
 * producer settings of application.properties. There is no scheduling and no wake-up here: each test calls
 * {@code drain()} itself. Needs Docker; skipped without it (CI fails if a *PostgresTest is skipped).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OutboxRelayPostgresTest.TestApplication.class, properties = "careplan.queue.type=kafka")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayPostgresTest {

    private static final int BATCH = 3;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

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
    private OutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ProducerFactory<String, String> producerFactory;

    private String topic;

    @BeforeEach
    void emptyOutboxAndFreshTopic() throws Exception {
        jdbc.update("DELETE FROM outbox_event");
        topic = "test-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("a row that can never be sent stops one run, then moves behind the others and does not hold them up")
    void aPermanentlyFailingRowDoesNotBlockTheRowsBehindIt() {
        // Given: the oldest row has a topic name Kafka rejects; three good rows were written after it
        insert("not a valid topic", 1);
        insert(topic, 2);
        insert(topic, 3);
        insert(topic, 4);
        OutboxRelay relay = relay(kafka);

        // When: the first run meets the bad row first and stops there
        relay.drain();

        assertThat(sentKeys()).isEmpty();
        assertThat(attemptsOf(1)).isEqualTo(1);

        // Then: on the next run the good rows come first, because they have fewer failed attempts
        relay.drain();

        assertThat(sentKeys()).containsExactlyInAnyOrder("2", "3", "4");
        assertThat(KafkaTestReader.readAll(KAFKA.getBootstrapServers(), topic))
                .extracting(ConsumerRecord::key)
                .containsExactlyInAnyOrder("2", "3", "4");
        // the bad row is still there, unsent, counted, with the class name of its failure and nothing else
        Map<String, Object> bad = jdbc.queryForMap("SELECT * FROM outbox_event WHERE aggregate_id = 1");
        assertThat(bad.get("sent_at")).isNull();
        assertThat((Integer) bad.get("attempts")).isEqualTo(2);
        assertThat((String) bad.get("last_error")).endsWith("Exception").doesNotContain(" ");
    }

    @Test
    @DisplayName("two senders at once: the second skips the rows the first has locked, so no row is sent twice")
    void concurrentSendersNeverSendTheSameRow() throws Exception {
        // Given: five rows; sender A takes three and is held in the middle of its first send, keeping its locks
        for (int id = 1; id <= 5; id++) {
            insert(topic, id);
        }
        CountDownLatch aHoldsItsRows = new CountDownLatch(1);
        CountDownLatch letAContinue = new CountDownLatch(1);
        KafkaTemplate<String, String> slow = new KafkaTemplate<>(producerFactory) {
            @Override
            public CompletableFuture<SendResult<String, String>> send(String toTopic, String key, String data) {
                aHoldsItsRows.countDown();
                try {
                    assertThat(letAContinue.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.send(toTopic, key, data);
            }
        };
        ExecutorService threadA = Executors.newSingleThreadExecutor();
        try {
            Future<?> a = threadA.submit(relay(slow)::drain);
            assertThat(aHoldsItsRows.await(30, TimeUnit.SECONDS)).isTrue();

            // When: sender B runs while A still holds rows 1-3
            relay(kafka).drain();

            // Then: B sent exactly the two rows A had not locked, and did not wait for the others
            assertThat(sentKeys()).containsExactlyInAnyOrder("4", "5");
            assertThat(a.isDone()).isFalse();

            letAContinue.countDown();
            a.get(30, TimeUnit.SECONDS);
        } finally {
            letAContinue.countDown();
            threadA.shutdown();
        }

        assertThat(sentKeys()).containsExactlyInAnyOrder("1", "2", "3", "4", "5");
        assertThat(KafkaTestReader.readAll(KAFKA.getBootstrapServers(), topic))
                .extracting(ConsumerRecord::key)
                .containsExactlyInAnyOrder("1", "2", "3", "4", "5"); // each exactly once
        assertThat(jdbc.queryForObject("SELECT sum(attempts) FROM outbox_event", Integer.class)).isZero();
    }

    @Test
    @DisplayName("clean-up deletes only rows sent longer ago than the retention, never an unsent one")
    void cleanupDeletesOnlyOldSentRows() {
        insert(topic, 1); // sent 8 days ago
        insert(topic, 2); // sent 6 days ago
        insert(topic, 3); // never sent, written 30 days ago
        jdbc.update("UPDATE outbox_event SET sent_at = now() - INTERVAL '8 days' WHERE aggregate_id = 1");
        jdbc.update("UPDATE outbox_event SET sent_at = now() - INTERVAL '6 days' WHERE aggregate_id = 2");
        jdbc.update("UPDATE outbox_event SET created_at = now() - INTERVAL '30 days' WHERE aggregate_id = 3");

        new OutboxCleanup(outbox, Duration.ofDays(7)).removeOldSentRows();

        assertThat(jdbc.queryForList("SELECT aggregate_id FROM outbox_event ORDER BY aggregate_id", Long.class))
                .containsExactly(2L, 3L);
    }

    private OutboxRelay relay(KafkaTemplate<String, String> template) {
        return new OutboxRelay(outbox, template, transactionManager, BATCH, SEND_TIMEOUT);
    }

    private void insert(String toTopic, long aggregateId) {
        outbox.insert(UUID.randomUUID(), aggregateId, toTopic, String.valueOf(aggregateId), "{}");
    }

    private List<String> sentKeys() {
        return jdbc.queryForList(
                "SELECT message_key FROM outbox_event WHERE sent_at IS NOT NULL ORDER BY id", String.class);
    }

    private int attemptsOf(long aggregateId) {
        return jdbc.queryForObject(
                "SELECT attempts FROM outbox_event WHERE aggregate_id = ?", Integer.class, aggregateId);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            KafkaAutoConfiguration.class
    })
    @Import({OutboxRepository.class, KafkaProducerConfig.class})
    static class TestApplication {
    }
}
