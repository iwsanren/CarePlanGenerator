package com.page24.backend.service;

import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.BlockError;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.QueueTestSupport.CarePlanRow;
import com.page24.backend.service.QueueTestSupport.ControllableLlm;
import com.page24.backend.service.QueueTestSupport.Database;
import com.page24.backend.service.QueueTestSupport.RedisCli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * CP-C0, risk 1: the Redis queue forgets a task the moment a worker takes it, so a worker process that dies
 * after the pop leaves the care plan stuck for good.
 *
 * <b>These tests document an existing defect.</b> They are green because they assert the defect is there.
 * After the move to Kafka, CP-C6 replaces them with the opposite tests (a task whose consumer dies is delivered
 * again and the plan reaches COMPLETED).
 *
 * <h2>How "the process dies" is simulated, and why that is enough</h2>
 * The defect is the state left behind in the two external stores (Redis and PostgreSQL), not the way a JVM
 * dies, so no real process is killed. The "old process" is a worker thread frozen inside the LLM call. Everything
 * the tests then check is read from outside the application: redis-cli inside the container and a fresh JDBC
 * connection per query (committed rows only). The "restarted process" is a brand-new {@link CarePlanWorker}
 * that is given nothing except what survives in Redis and PostgreSQL, exactly like a process after a redeploy.
 *
 * The application context is built without {@code @EnableScheduling} and without a worker bean, so the 30 s
 * poll and the after-commit event can never start a worker on their own; each test starts the workers itself.
 *
 * Needs Docker (PostgreSQL and Redis containers); skipped without it.
 */
@SpringBootTest(classes = QueueCrashLossRedisTest.TestApplication.class, properties = {
        // "create", not "create-drop": at JVM exit the containers are already gone and a drop would hang for 30 s
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN",
        "llm.mock.enabled=true",
        "llm.provider=local"
})
@Testcontainers(disabledWithoutDocker = true)
class QueueCrashLossRedisTest {

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
    private QueueService queueService;

    @Autowired
    private CarePlanRepository carePlanRepository;

    @Autowired
    private CarePlanGenerationService generationService;

    @Autowired
    private ControllableLlm llm;

    private final RedisCli redis = new RedisCli(REDIS);
    private final Database database = new Database(POSTGRES);
    private ExecutorService oldProcess;

    @BeforeEach
    void startClean() {
        redis.flushAll();
        llm.reset();
        oldProcess = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopTheFrozenThread() throws InterruptedException {
        llm.reset(); // lets the frozen "dead" worker finish so no thread outlives the test
        oldProcess.shutdown();
        assertThat(oldProcess.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("pop, then the process dies during generation: the task is gone from Redis and the plan is stuck in PROCESSING")
    void taskIsLostAndPlanStaysProcessingWhenTheProcessDiesDuringGeneration() throws Exception {
        // Given: an order was created, so its carePlanId is the only element of the Redis list
        OrderResponse order = orderService.createOrder(QueueTestSupport.newOrderRequest());
        long carePlanId = carePlanIdOf(order);
        assertThat(redis.items()).containsExactly(String.valueOf(carePlanId));

        // When: a worker pops it and is frozen inside the LLM call. From here on it plays the part of a process
        // that was killed (kill -9, OOM, redeploy): it will never write anything again during the assertions.
        llm.holdNextCalls();
        Future<?> dyingWorker = oldProcess.submit(() -> newWorker().pollQueue());
        await().atMost(Duration.ofSeconds(20)).until(llm::hasEntered);

        // Then: the queue no longer knows the task ...
        assertThat(redis.length()).isZero();
        // ... and PostgreSQL (a separate connection, so this is committed state) says PROCESSING with no result
        CarePlanRow stuck = database.find(carePlanId).orElseThrow();
        assertThat(stuck.status()).isEqualTo("PROCESSING");
        assertThat(stuck.startedAt()).isNotNull();
        assertThat(stuck.finishedAt()).isNull();
        assertThat(stuck.attemptCount()).isNull();

        // When: the application "restarts" and its worker polls. It has only Redis and PostgreSQL to go on.
        newWorker().pollQueue();

        // Then: nobody picks the plan up again. The only LLM call ever made is the frozen one.
        assertThat(llm.calls()).isEqualTo(1);
        assertThat(database.find(carePlanId).orElseThrow().status()).isEqualTo("PROCESSING");
        assertThat(dyingWorker.isDone()).isFalse();

        // And the user cannot get out of it either: both manual ways to change the plan refuse a PROCESSING plan.
        assertThatThrownBy(() -> orderService.regenerateCarePlan(order.getId()))
                .isInstanceOfSatisfying(BlockError.class,
                        e -> assertThat(e.getCode()).isEqualTo("CAREPLAN_ALREADY_PROCESSING"));
        assertThatThrownBy(() -> orderService.uploadCarePlan(order.getId(), "manual text", null))
                .isInstanceOfSatisfying(BlockError.class,
                        e -> assertThat(e.getCode()).isEqualTo("CAREPLAN_ALREADY_PROCESSING"));
    }

    @Test
    @DisplayName("pop, then the process dies before PROCESSING is saved: the task is gone and the plan is stuck in PENDING")
    void taskIsLostAndPlanStaysPendingWhenTheProcessDiesRightAfterThePop() {
        // Given: an order was created, so its carePlanId is in the Redis list
        OrderResponse order = orderService.createOrder(QueueTestSupport.newOrderRequest());
        long carePlanId = carePlanIdOf(order);
        assertThat(redis.items()).containsExactly(String.valueOf(carePlanId));

        // When: a worker pops the id and dies immediately. The id now exists only in a local variable that is
        // simply dropped. (The gap between leftPop and markProcessing is microseconds wide, so it cannot be
        // frozen from outside; the pop is done by hand and nothing else happens, which is the same final state.)
        Long popped = queueService.dequeue();
        assertThat(popped).isEqualTo(carePlanId);

        // And the application restarts and polls
        newWorker().pollQueue();

        // Then: no LLM call ever happens, the queue is empty, and the plan is still PENDING
        assertThat(llm.calls()).isZero();
        assertThat(redis.length()).isZero();
        CarePlanRow stuck = database.find(carePlanId).orElseThrow();
        assertThat(stuck.status()).isEqualTo("PENDING");
        assertThat(stuck.startedAt()).isNull();

        // Regenerate is refused for a plan that "has not finished its first generation yet"; a manual upload is
        // the only way out (it is not blocked for PENDING).
        assertThatThrownBy(() -> orderService.regenerateCarePlan(order.getId()))
                .isInstanceOfSatisfying(BlockError.class,
                        e -> assertThat(e.getCode()).isEqualTo("CAREPLAN_NOT_READY_FOR_REGENERATION"));
        orderService.uploadCarePlan(order.getId(), "manual text", null);
        assertThat(database.find(carePlanId).orElseThrow().status()).isEqualTo("COMPLETED");
    }

    /** A worker as a freshly started process would build it: only the same Redis and database behind it. */
    private CarePlanWorker newWorker() {
        return new CarePlanWorker(queueService, carePlanRepository, generationService);
    }

    private long carePlanIdOf(OrderResponse order) {
        return carePlanRepository.findByOrderId(order.getId()).orElseThrow().getId();
    }

    /**
     * The real services around the real queue and repositories, nothing else: no scheduling, no worker bean, no
     * security, no web layer. {@link ControllableLlm} replaces the local mock under the same bean name.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableRetry
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
            QueueService.class,
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
    }
}
