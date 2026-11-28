package com.page24.backend.service;

import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.BlockError;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.QueueTestSupport.CarePlanRow;
import com.page24.backend.service.QueueTestSupport.ControllableLlm;
import com.page24.backend.service.QueueTestSupport.Database;
import com.page24.backend.service.QueueTestSupport.RedisCli;
import com.page24.backend.service.QueueTestSupport.RetryFailureRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * CP-C0, risk 2: {@code OrderService} pushes the carePlanId onto the Redis list <i>inside</i> its database
 * transaction, so the id becomes visible to a worker before the row it points to is committed (and stays in
 * Redis if the transaction rolls back).
 *
 * <b>These tests document the existing behaviour.</b> They are green because they assert what happens today.
 * They stay for as long as the Redis queue does: it is still the default and the path to fall back to. The
 * opposites for the Kafka queue are in {@link CarePlanScenarioPostgresTest} (no message before the commit,
 * regenerate through Kafka) and in OutboxKafkaPostgresTest (a rolled-back order leaves nothing to send).
 *
 * <h2>What these tests found (read together)</h2>
 * <ul>
 *   <li>The window is real, but it is closed by {@code @Retryable}, not by design: a worker that pops the id too
 *       early fails its first attempt and looks again after 2 s, by which time a normal commit (milliseconds) has
 *       happened. The plan still reaches COMPLETED. See the 2a-1 test.</li>
 *   <li>Only when the commit comes later than the whole retry window (2 s + 4 s) is the task lost and the plan
 *       left in PENDING. See the 2a-2 test.</li>
 *   <li>A rollback leaves a "ghost" id in Redis. Its cost is a worker that spins for about 6 s on it while every
 *       task behind it waits (head-of-line blocking), not a lost order. See the 2b tests.</li>
 *   <li>The event trigger of the order's <i>own</i> id is safe, because it fires after commit (2c, control).</li>
 *   <li>The regenerate path never hits "row not found" (the row already exists, so the first guess about it was
 *       wrong). What it hits instead is a lost update: JPA writes at commit, so regenerate's UPDATE lands after
 *       whatever the early worker wrote and overwrites it. With a normal commit the plan reads PENDING while the
 *       LLM runs and still ends COMPLETED (2d-1). Only if the commit comes after the whole generation is the
 *       finished result overwritten and the plan left in PENDING (2d-2).</li>
 * </ul>
 *
 * <h2>How the timing is controlled without sleeps</h2>
 * A {@link TransactionTemplate} wraps the real {@code OrderService.createOrder}; the service's own
 * {@code @Transactional} joins that outer transaction, so the real code runs unchanged but the commit moment
 * belongs to the test. A second thread calling {@code worker.pollQueue()} stands in for the 30 s timer. The
 * context is built without {@code @EnableScheduling}, so the real timer can never fire by itself. Every wait is
 * an Awaitility condition (a Redis length, a recorded retry failure, a database lock wait, a row status).
 *
 * Needs Docker (PostgreSQL and Redis containers); skipped without it.
 */
@SpringBootTest(classes = EnqueueBeforeCommitRaceRedisTest.TestApplication.class, properties = {
        // "create", not "create-drop": at JVM exit the containers are already gone and a drop would hang for 30 s
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN",
        "llm.mock.enabled=true",
        "llm.provider=local"
})
@Testcontainers(disabledWithoutDocker = true)
class EnqueueBeforeCommitRaceRedisTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private CarePlanWorker worker;

    @Autowired
    private CarePlanRepository carePlanRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ControllableLlm llm;

    @Autowired
    private RetryFailureRecorder retryFailures;

    @Autowired
    @Qualifier("carePlanTaskExecutor")
    private java.util.concurrent.Executor eventWorkerPool;

    private final RedisCli redis = new RedisCli(REDIS);
    private final Database database = new Database(POSTGRES);
    private TransactionTemplate transaction;
    private ExecutorService timerThread;

    @BeforeEach
    void startClean() {
        redis.flushAll();
        llm.reset();
        retryFailures.reset();
        transaction = new TransactionTemplate(transactionManager);
        timerThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopEveryThread() throws InterruptedException {
        llm.reset();
        timerThread.shutdown();
        assertThat(timerThread.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        // the pool that runs the after-commit event trigger must be idle before the next test clears Redis
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventWorkerPool;
        await().atMost(WAIT).until(() -> pool.getActiveCount() == 0);
    }

    @Test
    @DisplayName("2c control: a normal order is picked up by the after-commit event and completes on the first attempt")
    void normalOrderIsProcessedByTheAfterCommitEventOnTheFirstAttempt() {
        // When: an order is created the normal way (its own transaction; the event fires after the commit)
        OrderResponse order = orderService.createOrder(QueueTestSupport.newOrderRequest());
        long carePlanId = carePlanIdOf(order);

        // Then: it completes with one attempt and no failed attempt, so the id was never popped too early
        awaitStatus(carePlanId, "COMPLETED");
        CarePlanRow row = database.find(carePlanId).orElseThrow();
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.startedAt()).isNotNull();
        assertThat(row.finishedAt()).isNotNull();
        assertThat(retryFailures.failures()).isZero();
        assertThat(redis.length()).isZero();
    }

    @Test
    @DisplayName("2a-1: the timer pops the id before a fast commit: the row is not found, but the retry masks it and the plan still COMPLETES, after about 2 s")
    void idPoppedBeforeAFastCommitIsMaskedByTheRetryAfterAboutTwoSeconds() throws Exception {
        // When: createOrder has pushed the id (row still uncommitted) and the timer fires at that exact moment
        OrderResponse order = transaction.execute(status -> {
            OrderResponse created = orderService.createOrder(QueueTestSupport.newOrderRequest());
            timerThread.submit(worker::pollQueue);
            // Attempt 1 of generateWithRetry has failed: the worker looked for the row and it was invisible.
            await().atMost(WAIT).until(() -> retryFailures.failures() >= 1);
            return created; // returning commits, a few milliseconds later: far inside the 2 s back-off
        });
        long carePlanId = carePlanIdOf(order);

        // Then: it is NOT lost. Attempt 2, 2 s later, finds the committed row. This is luck, not design: the plan
        // was only saved by the retry window being much longer than a commit.
        awaitStatus(carePlanId, "COMPLETED");
        CarePlanRow row = database.find(carePlanId).orElseThrow();
        assertThat(row.attemptCount()).isEqualTo(2);
        assertThat(row.content()).startsWith(ControllableLlm.GENERATED);
        assertThat(redis.length()).isZero();

        // A side effect of the early pop: processTask skipped markProcessing (it found no row), so the plan never
        // went through PROCESSING and started_at stayed NULL ("no data" for the dashboard's timing statistics).
        assertThat(row.startedAt()).isNull();
    }

    @Test
    @DisplayName("2a-2: the timer pops the id and the commit comes after the whole retry window (about 6 s): the task is lost and the plan stays PENDING")
    void idPoppedBeforeACommitThatIsLaterThanTheRetryWindowIsLostAndPlanStaysPending() {
        // When: the id is pushed, the timer pops it, and the commit is held back until the worker has used up all
        // three attempts (at 0 s, 2 s and 6 s) and given up. The transaction stays open for those ~6 s.
        OrderResponse order = transaction.execute(status -> {
            OrderResponse created = orderService.createOrder(QueueTestSupport.newOrderRequest());
            Future<?> poll = timerThread.submit(worker::pollQueue);
            waitFor(poll);
            return created; // only now does the transaction commit
        });
        long carePlanId = carePlanIdOf(order);

        // Then: the worker failed 3 times and its @Recover found no row to mark FAILED, so nothing was recorded
        assertThat(retryFailures.failures()).isEqualTo(3);
        assertThat(llm.calls()).isZero();
        assertThat(redis.length()).isZero();

        // The row now exists but nobody will ever process it: it stays PENDING, even across a later timer run
        // and the after-commit event of this very order (both drain an empty queue).
        worker.pollQueue();
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .until(() -> "PENDING".equals(database.find(carePlanId).orElseThrow().status()));
        CarePlanRow stuck = database.find(carePlanId).orElseThrow();
        assertThat(stuck.startedAt()).isNull();
        assertThat(stuck.finishedAt()).isNull();
        assertThat(stuck.attemptCount()).isNull();
        assertThat(llm.calls()).isZero();

        // and the user cannot regenerate a plan that "has not finished its first generation yet"
        assertThatThrownBy(() -> orderService.regenerateCarePlan(order.getId()))
                .isInstanceOfSatisfying(BlockError.class,
                        e -> assertThat(e.getCode()).isEqualTo("CAREPLAN_NOT_READY_FOR_REGENERATION"));
    }

    @Test
    @DisplayName("2b-1: the transaction rolls back after the push: a ghost id stays in Redis, pointing at a row that never existed")
    void rolledBackOrderLeavesAGhostIdInRedis() {
        // When: createOrder pushes the id and then the transaction rolls back (for example toResponse throws, or
        // the commit itself fails). The after-commit event never fires.
        long ghostId = createRolledBackOrder();

        // Then: Redis still holds the id although the database never got the row
        assertThat(redis.items()).containsExactly(String.valueOf(ghostId));
        assertThat(database.find(ghostId)).isEmpty();
        assertThat(llm.calls()).isZero();
    }

    @Test
    @DisplayName("2b-2: the ghost id costs the worker about 6 s of retries and makes the next real order wait behind it")
    void ghostIdBlocksTheNextRealOrderForAboutSixSeconds() {
        // Given: a ghost id at the head of the queue
        long ghostId = createRolledBackOrder();
        assertThat(redis.items()).containsExactly(String.valueOf(ghostId));

        // When: a real order is created normally, so its after-commit event starts a drain
        Instant created = Instant.now();
        OrderResponse order = orderService.createOrder(QueueTestSupport.newOrderRequest());
        long carePlanId = carePlanIdOf(order);
        assertThat(carePlanId).isNotEqualTo(ghostId); // PostgreSQL never hands the rolled-back id out again

        // Then: the worker pops the ghost first and burns its three attempts (0 s, 2 s, 6 s) before it reaches
        // the real order, which therefore completes no earlier than about 6 s after it was created. The control
        // test (2c) shows the same order completing on the first attempt when no ghost is in front of it.
        awaitStatus(carePlanId, "COMPLETED");
        assertThat(Duration.between(created, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(retryFailures.failures()).isEqualTo(3); // all three failures belong to the ghost
        CarePlanRow row = database.find(carePlanId).orElseThrow();
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.startedAt()).isNotNull();
        assertThat(redis.length()).isZero();
        assertThat(database.find(ghostId)).isEmpty(); // the ghost is simply dropped, nothing is lost
    }

    @Test
    @DisplayName("2d-1 regenerate, fast commit: the worker pops before the commit and runs from the OLD row, the commit overwrites its PROCESSING with PENDING, yet the plan still ends COMPLETED")
    void regenerateWithAFastCommitIsOverwrittenToPendingWhileTheLlmRunsButStillCompletes() {
        // Given: a finished plan with the content of its first run; the next LLM call will wait for the test
        OrderResponse order = orderService.createOrder(QueueTestSupport.newOrderRequest());
        long carePlanId = carePlanIdOf(order);
        awaitStatus(carePlanId, "COMPLETED");
        String firstRunContent = database.find(carePlanId).orElseThrow().content();
        assertThat(firstRunContent).isEqualTo(ControllableLlm.GENERATED + " #1");
        llm.holdNextCalls();

        // When: regenerate has reset the plan and pushed the id (nothing flushed yet: JPA writes at commit),
        // and the timer pops the id right then
        transaction.executeWithoutResult(status -> {
            orderService.regenerateCarePlan(order.getId());
            timerThread.submit(worker::pollQueue);
            await().atMost(WAIT).until(llm::hasEntered);
            // There is no "row not found" here: the row exists. The worker read the OLD committed version and
            // saved PROCESSING on top of it (no lock wait, because this transaction has not written yet).
            CarePlanRow seenByWorker = database.find(carePlanId).orElseThrow();
            assertThat(seenByWorker.status()).isEqualTo("PROCESSING");
            assertThat(seenByWorker.content()).isEqualTo(firstRunContent);
        }); // the commit flushes regenerate's UPDATE now, over the worker's row

        // Then: while the LLM is still running, the plan reads PENDING again with no start time
        CarePlanRow afterCommit = database.find(carePlanId).orElseThrow();
        assertThat(afterCommit.status()).isEqualTo("PENDING");
        assertThat(afterCommit.content()).isNull();
        assertThat(afterCommit.startedAt()).isNull();

        // But the worker is still inside its generation. It loaded the plan BEFORE the LLM call, so when the LLM
        // answers it saves its own in-memory copy (PROCESSING's started_at included) as COMPLETED, over the PENDING
        // row. The final state is therefore right, and started_at is back.
        llm.release();
        awaitStatus(carePlanId, "COMPLETED");
        CarePlanRow done = database.find(carePlanId).orElseThrow();
        assertThat(done.content()).isEqualTo(ControllableLlm.GENERATED + " #2");
        assertThat(done.attemptCount()).isEqualTo(1);
        assertThat(done.startedAt()).isNotNull();
        assertThat(done.finishedAt()).isNotNull();
        assertThat(retryFailures.failures()).isZero();
    }

    @Test
    @DisplayName("2d-2 regenerate, commit later than the generation: the commit overwrites the finished result, the plan is stuck in PENDING with no content and nothing queued")
    void regenerateWhoseCommitComesAfterTheGenerationLosesTheResultAndStaysPending() {
        // Given: a finished plan with the content of its first run
        OrderResponse order = orderService.createOrder(QueueTestSupport.newOrderRequest());
        long carePlanId = carePlanIdOf(order);
        awaitStatus(carePlanId, "COMPLETED");
        assertThat(database.find(carePlanId).orElseThrow().content()).isEqualTo(ControllableLlm.GENERATED + " #1");

        // When: the timer pops the id before the commit and the worker finishes the WHOLE generation (the LLM
        // answers at once) while regenerate's transaction is still open
        transaction.executeWithoutResult(status -> {
            orderService.regenerateCarePlan(order.getId());
            timerThread.submit(worker::pollQueue);
            await().atMost(WAIT).until(() -> (ControllableLlm.GENERATED + " #2")
                    .equals(database.find(carePlanId).orElseThrow().content()));
        }); // only now does regenerate's UPDATE get flushed, over the finished result

        // Then: the generated content is gone, the plan is PENDING, and nothing is left in Redis to redo it
        CarePlanRow stuck = database.find(carePlanId).orElseThrow();
        assertThat(stuck.status()).isEqualTo("PENDING");
        assertThat(stuck.content()).isNull();
        assertThat(stuck.finishedAt()).isNull();
        assertThat(stuck.attemptCount()).isNull();
        assertThat(llm.calls()).isEqualTo(2); // the second generation really ran; its result was thrown away
        assertThat(redis.length()).isZero();

        // It stays that way, and the user cannot regenerate a PENDING plan either
        worker.pollQueue();
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .until(() -> "PENDING".equals(database.find(carePlanId).orElseThrow().status()));
        assertThatThrownBy(() -> orderService.regenerateCarePlan(order.getId()))
                .isInstanceOfSatisfying(BlockError.class,
                        e -> assertThat(e.getCode()).isEqualTo("CAREPLAN_NOT_READY_FOR_REGENERATION"));
    }

    /** A rolled-back createOrder: returns the carePlanId that was pushed to Redis and then discarded. */
    private long createRolledBackOrder() {
        transaction.executeWithoutResult(status -> {
            orderService.createOrder(QueueTestSupport.newOrderRequest());
            status.setRollbackOnly();
        });
        List<String> queued = redis.items();
        assertThat(queued).hasSize(1);
        return Long.parseLong(queued.get(0));
    }

    private void awaitStatus(long carePlanId, String expected) {
        await().atMost(WAIT).until(() -> database.find(carePlanId)
                .map(row -> expected.equals(row.status())).orElse(false));
    }

    private long carePlanIdOf(OrderResponse order) {
        return carePlanRepository.findByOrderId(order.getId()).orElseThrow().getId();
    }

    private static void waitFor(Future<?> future) {
        try {
            future.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The real services around the real queue and repositories, with the real worker bean and its after-commit
     * event, but without {@code @EnableScheduling}: the 30 s poll never fires on its own here, the tests call
     * {@code worker.pollQueue()} themselves at the moment they want the timer to "fire".
     */
    @Configuration(proxyBeanMethods = false)
    @EnableRetry
    @EnableAsync
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            DataRedisAutoConfiguration.class
    })
    @EntityScan("com.page24.backend.entity")
    @EnableJpaRepositories("com.page24.backend.repository")
    @Import({
            com.page24.backend.config.RedisConfig.class,
            com.page24.backend.config.AsyncConfig.class,
            QueueService.class,
            CarePlanWorker.class,
            OrderService.class,
            com.page24.backend.dto.OrderMapper.class,
            CarePlanGenerationService.class,
            LLMAdapterFactory.class
    })
    static class TestApplication {

        @Bean("local")
        ControllableLlm controllableLlm() {
            return new ControllableLlm();
        }

        @Bean
        RetryFailureRecorder retryFailureRecorder() {
            return new RetryFailureRecorder();
        }
    }
}
