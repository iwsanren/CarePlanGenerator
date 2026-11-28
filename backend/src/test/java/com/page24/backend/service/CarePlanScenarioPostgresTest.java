package com.page24.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.outbox.CarePlanRequestedEvent;
import com.page24.backend.outbox.KafkaTestReader;
import com.page24.backend.outbox.OutboxRelay;
import com.page24.backend.outbox.OutboxRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CP-C6: the failure scenarios of the Kafka path, end to end (order, outbox, Kafka, consumer, local mock LLM) on a
 * real Kafka and PostgreSQL. Faults are injected with spies; no production code knows about the tests. Several of
 * these are the opposites of the defects CP-C0 documented for the Redis queue.
 *
 * <b>How a consumer "crashes".</b> A spy throws an {@link Error}. Spring Kafka then stops the listener container
 * without committing the offset and closes the consumer, so the database and the committed offsets are left exactly
 * as a killed process leaves them. The one difference from {@code kill -9}: the consumer leaves the group cleanly, so
 * Kafka can redeliver at once instead of after the session timeout. The test restarts the container.
 *
 * The topics and the consumer group have names of their own. One consumer thread keeps the order of events
 * predictable. Needs Docker; skipped without it (CI fails if a *PostgresTest is skipped).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "careplan.queue.type=kafka",
        "spring.kafka.listener.concurrency=1",
        // the sweeper is run by hand where a test needs it
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
class CarePlanScenarioPostgresTest {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "scenario-requested-" + SUFFIX;
    private static final String DEAD_LETTERS = "scenario-requested-dlt-" + SUFFIX;
    private static final String GROUP = "scenario-group-" + SUFFIX;
    private static final String PATIENT = "Quillon";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Duration SOON = Duration.ofSeconds(60);

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
        registry.add("careplan.kafka.topics.requested", () -> TOPIC);
        registry.add("careplan.kafka.topics.requested-dead-letter", () -> DEAD_LETTERS);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private CarePlanRepository carePlans;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private ConsumerStops consumerStops;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private CarePlanSweeper sweeper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private LocalLLM llm;

    @MockitoSpyBean
    private CarePlanClaimRepository claims;

    @MockitoSpyBean
    private OutboxRepository outbox;

    @MockitoSpyBean
    private CarePlanProcessor processor;

    private final Database database = new Database(POSTGRES);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void startFromAQuietSystem() {
        awaitQuiet();
        // Whatever is still PROCESSING belongs to no test that is running: the PROCESSING example plan of the demo
        // data (the sweeper may or may not have seen it at startup) or a plan an earlier, failed test left behind.
        jdbc.update("UPDATE care_plans SET status = 'FAILED' WHERE status = 'PROCESSING'");
        consumerStops.reasons.clear();
        logs.start();
        rootLogger().addAppender(logs);
    }

    /** Runs also after a failed test: no injected fault and no stopped consumer may reach the next one. */
    @AfterEach
    void removeFaultsAndRestartTheConsumer() {
        rootLogger().detachAppender(logs);
        Mockito.reset(llm, claims, outbox, processor);
        if (!consumerStops.reasons.isEmpty() || !listener().isRunning()) {
            restartConsumer();
        }
        awaitQuiet();
    }

    // ---------------------------------------------------------------- consumer crashes

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("consumer crashes BEFORE the claim: nothing is committed, the redelivery claims it for the first time")
    void crashBeforeTheClaimIsRedelivered() throws Exception {
        AtomicBoolean crash = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (crash.getAndSet(false)) {
                throw new Error("simulated crash before the claim");
            }
            return invocation.callRealMethod();
        }).when(claims).claim(anyLong(), any(), any(), anyInt());

        Order order = placeOrder();

        awaitCrash();
        assertThat(status(order)).isEqualTo("PENDING");
        assertThat(claimToken(order)).isZero();
        assertThat(consumerLag()).isEqualTo(1); // the offset was not committed

        restartConsumer();

        awaitStatus(order, "COMPLETED");
        assertThat(claimToken(order)).isEqualTo(1);
        assertThat(llmCalls(order)).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("consumer crashes AFTER the claim: the redelivery takes over at once, without waiting for the lease")
    void crashAfterTheClaimIsRedeliveredAndTakenOver() throws Exception {
        AtomicBoolean crash = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (crash.getAndSet(false)) {
                throw new Error("simulated crash before the result is written");
            }
            return invocation.callRealMethod();
        }).when(claims).complete(anyLong(), anyInt(), anyString(), anyInt());

        Order order = placeOrder();

        awaitCrash();
        assertThat(status(order)).isEqualTo("PROCESSING"); // what CP-C0 left stuck for good
        assertThat(claimToken(order)).isEqualTo(1);
        assertThat(consumerLag()).isEqualTo(1);
        // the lease still has minutes to run, so only "the same message came back" can take the plan over
        assertThat(jdbc.queryForObject("SELECT lease_expires_at > now() + INTERVAL '5 minutes' "
                + "FROM care_plans WHERE id = ?", Boolean.class, order.carePlanId())).isTrue();

        restartConsumer();

        awaitStatus(order, "COMPLETED");
        assertThat(claimToken(order)).isEqualTo(2);
        assertThat(reclaimCount(order)).isEqualTo(1);
        assertThat(llmCalls(order)).isEqualTo(2); // the first result was lost with the "process"
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("the same message delivered again after the plan is finished: skipped, generated only once")
    void duplicateDeliveryGeneratesOnce() throws Exception {
        Order order = placeOrder();
        awaitStatus(order, "COMPLETED");
        String content = database.find(order.carePlanId()).orElseThrow().content();
        String sameMessage = jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE aggregate_id = ?", String.class, order.carePlanId());

        kafka.send(TOPIC, String.valueOf(order.carePlanId()), sameMessage).get(10, TimeUnit.SECONDS);

        verify(processor, timeout(SOON.toMillis()).times(2)).process(any(), eq(order.carePlanId()));
        awaitQuiet();
        assertThat(llmCalls(order)).isEqualTo(1);
        assertThat(claimToken(order)).isEqualTo(1);
        assertThat(database.find(order.carePlanId()).orElseThrow().content()).isEqualTo(content);
        assertThat(deadLetters(order)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("the sender fails after Kafka acknowledged and before marking the row: sent twice, generated once")
    void senderCrashAfterTheAcknowledgementGeneratesOnce() {
        AtomicBoolean crash = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (crash.getAndSet(false)) {
                throw new IllegalStateException("simulated: the process failed before sent_at was committed");
            }
            return invocation.callRealMethod();
        }).when(outbox).markSent(anyLong());

        Order order = placeOrder();

        verify(processor, timeout(SOON.toMillis()).times(2)).process(any(), eq(order.carePlanId()));
        awaitStatus(order, "COMPLETED");
        awaitQuiet();
        List<ConsumerRecord<String, String>> sent = KafkaTestReader.readAll(KAFKA.getBootstrapServers(), TOPIC)
                .stream().filter(r -> r.key().equals(String.valueOf(order.carePlanId()))).toList();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).value()).isEqualTo(sent.get(1).value()); // the same event, same eventId
        assertThat(llmCalls(order)).isEqualTo(1);
        assertThat(claimToken(order)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- failures

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("the LLM fails on every attempt: FAILED after 3 calls, offset committed, no dead letter")
    void llmFailureEndsFailedAndCommitted() throws Exception {
        doAnswer(invocation -> {
            throw new IllegalStateException("LLM down");
        }).when(llm).generateCarePlan(anyString());

        Order order = placeOrder();

        awaitStatus(order, "FAILED");
        CarePlanRow row = database.find(order.carePlanId()).orElseThrow();
        assertThat(row.errorMessage()).isEqualTo("LLM service unavailable");
        assertThat(row.attemptCount()).isEqualTo(3);
        assertThat(llmCalls(order)).isEqualTo(3);
        await().atMost(SOON).until(() -> consumerLag() == 0);
        assertThat(deadLetters(order)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("a technical failure that stays: 5 retries, then dead letter and FAILED; nothing about the patient leaks")
    void persistentFailureBeforeTheClaimIsDeadLettered() {
        doAnswer(invocation -> {
            throw failureQuotingThePatient();
        }).when(claims).claim(anyLong(), any(), any(), anyInt());

        Order order = placeOrder();

        // 1 + 2 + 4 + 8 + 16 s of back-off
        await().atMost(Duration.ofSeconds(90)).until(() -> deadLetters(order).size() == 1);
        awaitStatus(order, "FAILED");
        verify(processor, times(6)).process(any(), eq(order.carePlanId()));
        assertThat(llmCalls(order)).isZero();
        assertThat(database.find(order.carePlanId()).orElseThrow().errorMessage())
                .isEqualTo("Generation could not be completed; please regenerate");
        assertNothingAboutThePatient(deadLetters(order).get(0), order);
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("writing the result always fails: stops after 3 claims and 3 LLM calls, dead letter and FAILED")
    void persistentFailureToWriteTheResultStopsAtTheClaimLimit() {
        doAnswer(invocation -> {
            throw failureQuotingThePatient();
        }).when(claims).complete(anyLong(), anyInt(), anyString(), anyInt());

        Order order = placeOrder();

        await().atMost(Duration.ofSeconds(90)).until(() -> deadLetters(order).size() == 1);
        awaitStatus(order, "FAILED");
        assertThat(llmCalls(order)).isEqualTo(3);
        assertThat(reclaimCount(order)).isEqualTo(2);
        // 3 claims; giving the plan up raises the token once more, so that no late result can get in
        assertThat(claimToken(order)).isEqualTo(4);
        // three deliveries that claimed, a fourth that found the limit and was not retried
        verify(processor, times(4)).process(any(), eq(order.carePlanId()));
        assertNothingAboutThePatient(deadLetters(order).get(0), order);
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("a bad message on partition 2 reaches the dead letter, and the next message of that partition is handled")
    void badMessageOnPartitionTwoDoesNotBlockThePartition() throws Exception {
        Order order = placeOrder();
        awaitStatus(order, "COMPLETED");
        jdbc.update("UPDATE care_plans SET status = 'PENDING' WHERE id = ?", order.carePlanId());
        UUID eventId = UUID.randomUUID();
        String garbageKey = "garbage-" + order.carePlanId();

        kafka.send(TOPIC, 2, garbageKey, "{ not an event").get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, 2, String.valueOf(order.carePlanId()), event(eventId, order)).get(10, TimeUnit.SECONDS);

        awaitStatus(order, "COMPLETED");
        assertThat(claimToken(order)).isEqualTo(2);
        List<ConsumerRecord<String, String>> dead = deadLetters(garbageKey);
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).headers().lastHeader("kafka_dlt-original-partition").value())
                .containsExactly(0, 0, 0, 2);
    }

    // ---------------------------------------------------------------- backstop, and the CP-C0 opposites

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("sweeper: a PROCESSING plan that no message will bring back is requested again and completes")
    void sweeperRecoversAPlanWithoutAMessage() {
        Order order = placeOrder();
        awaitStatus(order, "COMPLETED");
        awaitQuiet();
        // as if the offset had moved past an unfinished plan: requested ten minutes ago, the lease has just run out
        jdbc.update("UPDATE outbox_event SET created_at = now() - INTERVAL '10 minutes' WHERE aggregate_id = ?",
                order.carePlanId());
        jdbc.update("UPDATE care_plans SET status = 'PROCESSING', content = NULL, "
                + "lease_expires_at = now() - INTERVAL '1 second' WHERE id = ?", order.carePlanId());

        sweeper.sweep();

        awaitStatus(order, "COMPLETED");
        assertThat(claimToken(order)).isEqualTo(2);
        assertThat(reclaimCount(order)).isEqualTo(1);
        assertThat(llmCalls(order)).isEqualTo(2);
    }

    @Test
    @WithMockUser(roles = "ASSISTANT")
    @DisplayName("opposite of CP-C0 2a: while the order's transaction is open there is no message, whoever sends")
    void noMessageBeforeTheCommit() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            Order order = transaction.execute(status -> {
                Order created = placeOrder();
                try {
                    // a sender running right now, in a transaction of its own
                    otherThread.submit(relay::drain).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                assertThat(KafkaTestReader.readAll(KAFKA.getBootstrapServers(), TOPIC))
                        .noneMatch(r -> r.key().equals(String.valueOf(created.carePlanId())));
                return created;
            });

            awaitStatus(order, "COMPLETED");
            assertThat(llmCalls(order)).isEqualTo(1);
            assertThat(database.find(order.carePlanId()).orElseThrow().attemptCount()).isEqualTo(1);
        } finally {
            otherThread.shutdown();
        }
    }

    @Test
    @WithMockUser(roles = "PHARMACIST")
    @DisplayName("opposite of CP-C0 2d: regenerate goes through Kafka and ends COMPLETED with the second generation")
    void regenerateIsGeneratedAgain() {
        Order order = placeOrder();
        awaitStatus(order, "COMPLETED");
        awaitQuiet();
        doAnswer(invocation -> "second generation").when(llm).generateCarePlan(anyString());

        orderService.regenerateCarePlan(order.orderId());

        await().atMost(SOON).until(() -> "second generation".equals(
                database.find(order.carePlanId()).orElseThrow().content()));
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(claimToken(order)).isEqualTo(2);
        assertThat(reclaimCount(order)).isZero(); // a new request, not a take-over
    }

    // ---------------------------------------------------------------- helpers

    /** An order of its own: the MRN is unique and marks the order's LLM calls. */
    private Order placeOrder() {
        int n = SEQUENCE.incrementAndGet();
        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName(PATIENT);
        request.setPatientLastName("Vexworth" + n);
        request.setPatientMrn(String.valueOf(987_000 + n));
        request.setPatientDateOfBirth(LocalDate.of(1980, 1, 1));
        request.setProviderName("Dr Scenario" + n);
        request.setProviderNpi(String.format("%010d", 1_600_000_000L + n));
        request.setMedicationName("IVIG");
        request.setPrimaryDiagnosis("G70.00");
        OrderResponse response = orderService.createOrder(request);
        long carePlanId = carePlans.findByOrderId(response.getId()).orElseThrow().getId();
        return new Order(response.getId(), carePlanId, request.getPatientMrn(), request.getPatientLastName());
    }

    private static DataAccessResourceFailureException failureQuotingThePatient() {
        // what a database or library message can look like; none of it may leave the process
        int n = SEQUENCE.get();
        return new DataAccessResourceFailureException(
                "Key (mrn)=(" + (987_000 + n) + ") of " + PATIENT + " Vexworth" + n + " could not be written");
    }

    private void assertNothingAboutThePatient(ConsumerRecord<String, String> dead, Order order) {
        List<Header> headers = StreamSupport.stream(dead.headers().spliterator(), false).toList();
        assertThat(headers).isNotEmpty();
        String headerText = headers.stream()
                .map(h -> h.key() + "=" + new String(h.value(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("\n"));
        assertThat(headerText + dead.value())
                .doesNotContain(PATIENT).doesNotContain(order.lastName()).doesNotContain(order.mrn());

        String logText = List.copyOf(logs.list).stream()
                .map(e -> e.getFormattedMessage() + (e.getThrowableProxy() == null
                        ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
        assertThat(logText)
                .contains("DataAccessResourceFailureException") // the capture did see the failures
                .doesNotContain(PATIENT).doesNotContain(order.lastName()).doesNotContain(order.mrn());
    }

    private long llmCalls(Order order) {
        return Mockito.mockingDetails(llm).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("generateCarePlan"))
                .filter(i -> i.getArgument(0, String.class).contains("MRN: " + order.mrn()))
                .count();
    }

    private String status(Order order) {
        return database.find(order.carePlanId()).orElseThrow().status();
    }

    private void awaitStatus(Order order, String expected) {
        await().atMost(SOON).until(() -> expected.equals(status(order)));
    }

    private int claimToken(Order order) {
        return jdbc.queryForObject(
                "SELECT claim_token FROM care_plans WHERE id = ?", Integer.class, order.carePlanId());
    }

    private int reclaimCount(Order order) {
        return jdbc.queryForObject(
                "SELECT reclaim_count FROM care_plans WHERE id = ?", Integer.class, order.carePlanId());
    }

    private String event(UUID eventId, Order order) {
        return JsonMapper.builder().build().writeValueAsString(
                CarePlanRequestedEvent.of(eventId, Instant.now(), order.carePlanId()));
    }

    private MessageListenerContainer listener() {
        return listeners.getListenerContainers().iterator().next();
    }

    private void awaitCrash() {
        await().atMost(SOON).until(() -> consumerStops.reasons.contains(ConsumerStoppedEvent.Reason.ERROR));
    }

    private void restartConsumer() {
        listener().stop();
        listener().start();
        consumerStops.reasons.clear();
    }

    /** Nothing left to send and nothing uncommitted: the consumer has finished with every message. */
    private void awaitQuiet() {
        await().atMost(Duration.ofSeconds(90)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE sent_at IS NULL", Integer.class) == 0
                && consumerLag() == 0);
    }

    private List<ConsumerRecord<String, String>> deadLetters(Order order) {
        return deadLetters(String.valueOf(order.carePlanId()));
    }

    private static List<ConsumerRecord<String, String>> deadLetters(String key) {
        return KafkaTestReader.readAll(KAFKA.getBootstrapServers(), DEAD_LETTERS).stream()
                .filter(record -> record.key().equals(key))
                .toList();
    }

    /** Records on the request topic that the consumer group has not committed yet. */
    private static long consumerLag() throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = List.of(0, 1, 2).stream()
                    .collect(Collectors.toMap(p -> new TopicPartition(TOPIC, p), p -> OffsetSpec.latest()));
            return admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS).entrySet().stream()
                    .mapToLong(end -> end.getValue().offset()
                            - (committed.get(end.getKey()) == null ? 0 : committed.get(end.getKey()).offset()))
                    .sum();
        }
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private record Order(long orderId, long carePlanId, String mrn, String lastName) {
    }

    /** Remembers why consumer threads stopped. */
    static class ConsumerStops {

        private final List<ConsumerStoppedEvent.Reason> reasons = new CopyOnWriteArrayList<>();

        @EventListener
        void stopped(ConsumerStoppedEvent event) {
            reasons.add(event.getReason());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Listeners {

        @Bean
        ConsumerStops consumerStops() {
            return new ConsumerStops();
        }
    }
}
