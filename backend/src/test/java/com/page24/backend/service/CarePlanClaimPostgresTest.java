package com.page24.backend.service;

import com.page24.backend.dto.OrderMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.CarePlan;
import com.page24.backend.outbox.OutboxRepository;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.CarePlanClaimRepository.ClaimState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The claim, the claim columns and the sweeper against a real PostgreSQL, set up like production on an empty
 * database: Flyway runs first (V5 finds no care_plans and does nothing), then Hibernate creates the table from the
 * entity. The conditions live in SQL, so a mock could not test them. Needs Docker; skipped without it (CI fails if a
 * *PostgresTest is skipped).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CarePlanClaimPostgresTest.TestApplication.class, properties = {
        "careplan.queue.type=kafka",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN"
})
class CarePlanClaimPostgresTest {

    private static final Duration LEASE = Duration.ofMinutes(6);
    private static final int MAX_RECLAIMS = 2;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private CarePlanRepository carePlans;

    @Autowired
    private CarePlanClaimRepository claims;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void forgetEarlierPlans() {
        // earlier tests' plans must not be picked up by a sweeper in this one
        jdbc.update("UPDATE care_plans SET status = 'COMPLETED' WHERE status IN ('PENDING', 'PROCESSING')");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    @DisplayName("empty database: Hibernate creates claim_token and reclaim_count NOT NULL DEFAULT 0, and a claim works")
    void claimColumnsHaveDefaultsOnAnEmptyDatabase() {
        assertThat(jdbc.queryForList("""
                SELECT column_name || ' ' || data_type || ' ' || is_nullable || ' ' || coalesce(column_default, 'null')
                FROM information_schema.columns
                WHERE table_name = 'care_plans'
                  AND column_name IN ('claim_token', 'claimed_event_id', 'lease_expires_at', 'reclaim_count')
                ORDER BY column_name""", String.class))
                .containsExactly(
                        "claim_token integer NO 0",
                        "claimed_event_id uuid YES null",
                        "lease_expires_at timestamp without time zone YES null",
                        "reclaim_count integer NO 0");
        // a row inserted by SQL that does not name the new columns gets their database defaults
        long planFromTheApplication = newPlan();
        long orderId = jdbc.queryForObject(
                "SELECT order_id FROM care_plans WHERE id = ?", Long.class, planFromTheApplication);
        jdbc.update("DELETE FROM care_plans WHERE id = ?", planFromTheApplication);
        long id = jdbc.queryForObject("INSERT INTO care_plans (order_id, status) VALUES (?, 'PENDING') RETURNING id",
                Long.class, orderId);
        assertThat(row(id)).containsEntry("claim_token", 0).containsEntry("reclaim_count", 0);

        assertThat(claims.claim(id, UUID.randomUUID(), LEASE, MAX_RECLAIMS)).hasValue(1);
    }

    @Test
    @DisplayName("success: a PENDING plan is claimed with token 1, PROCESSING, a start time and a lease in the future")
    void claimsAPendingPlan() {
        long id = newPlan();
        UUID event = UUID.randomUUID();

        assertThat(claims.claim(id, event, LEASE, MAX_RECLAIMS)).hasValue(1);

        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "PROCESSING").containsEntry("claim_token", 1)
                .containsEntry("reclaim_count", 0).containsEntry("claimed_event_id", event);
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("attempt_count")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT lease_expires_at > now() + INTERVAL '5 minutes' FROM care_plans WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("already COMPLETED or FAILED: not claimable")
    void doesNotClaimAFinishedPlan() {
        long id = newPlan();
        for (String finished : List.of("COMPLETED", "FAILED")) {
            jdbc.update("UPDATE care_plans SET status = ? WHERE id = ?", finished, id);
            UUID event = UUID.randomUUID();

            assertThat(claims.claim(id, event, LEASE, MAX_RECLAIMS)).isEmpty();
            assertThat(claims.stateOf(id, event).status()).isEqualTo(finished);
        }
        assertThat(row(id)).containsEntry("claim_token", 0);
    }

    @Test
    @DisplayName("PROCESSING with a live lease: another message cannot claim it")
    void doesNotTakeALiveLeaseFromAnotherMessage() {
        long id = newPlan();
        claims.claim(id, UUID.randomUUID(), LEASE, MAX_RECLAIMS);
        UUID other = UUID.randomUUID();

        assertThat(claims.claim(id, other, LEASE, MAX_RECLAIMS)).isEmpty();

        assertThat(claims.stateOf(id, other)).isEqualTo(new ClaimState("PROCESSING", 0, false));
        assertThat(row(id)).containsEntry("claim_token", 1);
    }

    @Test
    @DisplayName("PROCESSING with a live lease: the SAME message takes it over, and the old token can no longer write")
    void theSameMessageTakesOverAndTheOldTokenIsRefused() {
        long id = newPlan();
        UUID event = UUID.randomUUID();
        claims.claim(id, event, LEASE, MAX_RECLAIMS);
        jdbc.update("UPDATE care_plans SET started_at = now() - INTERVAL '10 minutes' WHERE id = ?", id);

        assertThat(claims.claim(id, event, LEASE, MAX_RECLAIMS)).hasValue(2);

        // the run being timed is the new one
        assertThat(jdbc.queryForObject(
                "SELECT started_at > now() - INTERVAL '1 minute' FROM care_plans WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(claims.complete(id, 1, "late result of the first claim", 1)).isFalse();
        assertThat(claims.fail(id, 1, "late failure", 3)).isFalse();
        assertThat(claims.complete(id, 2, "result", 1)).isTrue();
        assertThat(row(id)).containsEntry("status", "COMPLETED").containsEntry("content", "result")
                .containsEntry("attempt_count", 1).containsEntry("reclaim_count", 1)
                .containsEntry("lease_expires_at", null);
    }

    @Test
    @DisplayName("PROCESSING with an expired or missing lease: any message can claim it")
    void claimsAnExpiredLease() {
        long expired = newPlan();
        claims.claim(expired, UUID.randomUUID(), LEASE, MAX_RECLAIMS);
        expireLease(expired);
        long legacy = newPlan(); // as the Redis worker leaves it: PROCESSING and no lease
        jdbc.update("UPDATE care_plans SET status = 'PROCESSING' WHERE id = ?", legacy);

        assertThat(claims.claim(expired, UUID.randomUUID(), LEASE, MAX_RECLAIMS)).hasValue(2);
        assertThat(claims.claim(legacy, UUID.randomUUID(), LEASE, MAX_RECLAIMS)).hasValue(1);
    }

    @Test
    @DisplayName("claim limit: after two take-overs nothing can claim it; giving it up marks FAILED once")
    void stopsAtTheClaimLimit() {
        long id = newPlan();
        UUID event = UUID.randomUUID();
        assertThat(claims.claim(id, event, LEASE, MAX_RECLAIMS)).isPresent();
        assertThat(claims.claim(id, event, LEASE, MAX_RECLAIMS)).isPresent();
        assertThat(claims.claim(id, event, LEASE, MAX_RECLAIMS)).isPresent();

        assertThat(claims.claim(id, event, LEASE, MAX_RECLAIMS)).isEmpty();

        assertThat(claims.stateOf(id, event)).isEqualTo(new ClaimState("PROCESSING", 2, true));
        assertThat(claims.failAbandoned(id, event, "gave up")).isTrue();
        assertThat(claims.failAbandoned(id, event, "gave up")).isFalse();
        assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("error_message", "gave up");
        assertThat(claims.complete(id, 3, "too late", 1)).isFalse();
    }

    @Test
    @DisplayName("giving up never touches a plan another message holds")
    void abandoningLeavesAnotherOwnersPlanAlone() {
        long id = newPlan();
        claims.claim(id, UUID.randomUUID(), LEASE, MAX_RECLAIMS);

        assertThat(claims.failAbandoned(id, UUID.randomUUID(), "gave up")).isFalse();
        assertThat(claims.failAbandoned(9_999_999L, UUID.randomUUID(), "gave up")).isFalse();

        assertThat(row(id)).containsEntry("status", "PROCESSING");
    }

    @Test
    @DisplayName("a new request after a finished one starts counting take-overs again")
    void aFreshRequestResetsTheTakeOverCount() {
        long id = newPlan();
        UUID event = UUID.randomUUID();
        claims.claim(id, event, LEASE, MAX_RECLAIMS);
        claims.claim(id, event, LEASE, MAX_RECLAIMS);
        jdbc.update("UPDATE care_plans SET status = 'PENDING' WHERE id = ?", id);

        assertThat(claims.claim(id, UUID.randomUUID(), LEASE, MAX_RECLAIMS)).hasValue(3);

        assertThat(row(id)).containsEntry("reclaim_count", 0);
    }

    @Test
    @DisplayName("saving the whole entity (regenerate, upload, any save) cannot change the four claim columns; SQL can")
    void entitySavesNeverWriteTheClaimColumns() {
        OrderResponse order = orderService.createOrder(QueueTestSupport.newOrderRequest());
        long id = carePlans.findByOrderId(order.getId()).orElseThrow().getId();
        UUID event = UUID.randomUUID();

        // a copy read BEFORE the claim, as in CP-C0's lost update
        CarePlan stale = carePlans.findById(id).orElseThrow();
        OptionalInt token = claims.claim(id, event, LEASE, MAX_RECLAIMS);
        claims.complete(id, token.getAsInt(), "generated", 1);
        jdbc.update("UPDATE care_plans SET reclaim_count = 2, lease_expires_at = now() WHERE id = ?", id);
        Map<String, Object> claimed = claimColumns(id);
        assertThat(claimed).containsEntry("claim_token", 1).containsEntry("claimed_event_id", event);

        // a whole-entity save with a stale copy that even tries to set the columns
        stale.setClaimToken(99);
        stale.setReclaimCount(99);
        stale.setClaimedEventId(UUID.randomUUID());
        stale.setLeaseExpiresAt(null);
        stale.setContent("written by the entity");
        carePlans.save(stale);
        assertThat(claimColumns(id)).isEqualTo(claimed);
        assertThat(row(id)).containsEntry("content", "written by the entity");

        // the two real whole-entity paths
        jdbc.update("UPDATE care_plans SET status = 'COMPLETED' WHERE id = ?", id);
        orderService.regenerateCarePlan(order.getId());
        assertThat(claimColumns(id)).isEqualTo(claimed);
        orderService.uploadCarePlan(order.getId(), "manual text", null);
        assertThat(claimColumns(id)).isEqualTo(claimed);
        assertThat(row(id)).containsEntry("content", "manual text");
    }

    @Test
    @DisplayName("sweeper: an expired lease gets one new message, also when it scans twice; a live lease gets none")
    void sweeperRequestsAnExpiredPlanOnce() {
        long expired = newPlan();
        claims.claim(expired, UUID.randomUUID(), LEASE, MAX_RECLAIMS);
        expireLease(expired);
        long live = newPlan();
        claims.claim(live, UUID.randomUUID(), LEASE, MAX_RECLAIMS);

        sweeper(outbox).sweep();
        sweeper(outbox).sweep();

        assertThat(messagesFor(expired)).isEqualTo(1);
        assertThat(messagesFor(live)).isZero();
        assertThat(row(expired)).containsEntry("status", "PROCESSING"); // the consumer claims it, not the sweeper
    }

    @Test
    @DisplayName("sweeper: a PROCESSING row with neither lease nor start time gets one message, not one per scan")
    void sweeperDoesNotRepeatItselfForRowsWithoutTimestamps() {
        long id = newPlan();
        jdbc.update("UPDATE care_plans SET status = 'PROCESSING', started_at = NULL, lease_expires_at = NULL "
                + "WHERE id = ?", id);

        sweeper(outbox).sweep();
        sweeper(outbox).sweep();

        assertThat(messagesFor(id)).isEqualTo(1);
        assertThat(row(id).get("lease_expires_at")).isNotNull(); // "expired as of the first message"
        assertThat(claims.claim(id, UUID.randomUUID(), LEASE, MAX_RECLAIMS)).isPresent();
    }

    @Test
    @DisplayName("sweeper: over the claim limit the plan is marked FAILED and no message is written")
    void sweeperGivesUpOverTheClaimLimit() {
        long id = newPlan();
        claims.claim(id, UUID.randomUUID(), LEASE, MAX_RECLAIMS);
        jdbc.update("UPDATE care_plans SET reclaim_count = 2 WHERE id = ?", id);
        expireLease(id);

        sweeper(outbox).sweep();

        assertThat(row(id)).containsEntry("status", "FAILED")
                .containsEntry("error_message", "Generation could not be completed; please regenerate");
        assertThat(messagesFor(id)).isZero();
    }

    @Test
    @DisplayName("two sweepers at once: the second skips the row the first holds, so only one message is written")
    void concurrentSweepersDoNotDuplicate() throws Exception {
        long id = newPlan();
        claims.claim(id, UUID.randomUUID(), LEASE, MAX_RECLAIMS);
        expireLease(id);
        CountDownLatch firstHoldsTheRow = new CountDownLatch(1);
        CountDownLatch letItContinue = new CountDownLatch(1);
        OutboxRepository slowOutbox = new OutboxRepository(jdbc) {
            @Override
            public void insert(UUID eventId, long aggregateId, String topic, String key, String payload) {
                firstHoldsTheRow.countDown();
                try {
                    assertThat(letItContinue.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.insert(eventId, aggregateId, topic, key, payload);
            }
        };
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = thread.submit(sweeper(slowOutbox)::sweep);
            assertThat(firstHoldsTheRow.await(30, TimeUnit.SECONDS)).isTrue();

            sweeper(outbox).sweep(); // returns without waiting for the first

            assertThat(messagesFor(id)).isZero();
            letItContinue.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            letItContinue.countDown();
            thread.shutdown();
        }
        assertThat(messagesFor(id)).isEqualTo(1);
    }

    private CarePlanSweeper sweeper(OutboxRepository target) {
        return new CarePlanSweeper(claims, target, mock(ApplicationEventPublisher.class),
                JsonMapper.builder().build(), transactionManager, "careplan.requested", MAX_RECLAIMS);
    }

    private long newPlan() {
        OrderResponse order = orderService.createOrder(QueueTestSupport.newOrderRequest());
        return carePlans.findByOrderId(order.getId()).orElseThrow().getId();
    }

    private void expireLease(long id) {
        jdbc.update("UPDATE care_plans SET lease_expires_at = now() - INTERVAL '1 second' WHERE id = ?", id);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM care_plans WHERE id = ?", id);
    }

    private Map<String, Object> claimColumns(long id) {
        return jdbc.queryForMap("SELECT claim_token, claimed_event_id, lease_expires_at, reclaim_count "
                + "FROM care_plans WHERE id = ?", id);
    }

    private int messagesFor(long carePlanId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, carePlanId);
    }

    /** The real services around the database; the queue is a mock, so creating an order writes no message. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @EntityScan("com.page24.backend.entity")
    @EnableJpaRepositories("com.page24.backend.repository")
    @Import({OrderService.class, OrderMapper.class, CarePlanClaimRepository.class, OutboxRepository.class})
    static class TestApplication {

        @Bean
        CarePlanQueue carePlanQueue() {
            return mock(CarePlanQueue.class);
        }
    }
}
