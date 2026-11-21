package com.page24.backend.service;

import com.page24.backend.dto.CreateOrderRequest;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared helpers for the two "documented queue defect" test classes ({@link QueueCrashLossRedisTest} and
 * {@link EnqueueBeforeCommitRaceRedisTest}). Nothing in here is production code.
 *
 * The important design rule: every assertion about "what is really stored" goes through a connection that the
 * application does not own ({@link Database} opens a fresh JDBC connection per query, {@link RedisCli} runs
 * redis-cli inside the container). That way a test can never be fooled by a Hibernate first-level cache, an
 * uncommitted transaction or the application's own Redis template.
 */
final class QueueTestSupport {

    /** The Redis list that {@link QueueService} pushes carePlanIds onto. */
    static final String QUEUE_KEY = "careplan:queue";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private QueueTestSupport() {
    }

    /** A valid order request whose MRN, NPI and patient name are unique, so duplicate detection never fires. */
    static CreateOrderRequest newOrderRequest() {
        int n = SEQUENCE.incrementAndGet();
        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName("Queue");
        request.setPatientLastName("Patient" + n);
        request.setPatientMrn(String.format("%06d", 100_000 + n));
        request.setPatientDateOfBirth(LocalDate.of(1980, 1, 1));
        request.setProviderName("Dr Provider" + n);
        request.setProviderNpi(String.format("%010d", 1_000_000_000L + n));
        request.setMedicationName("IVIG");
        request.setPrimaryDiagnosis("G70.00");
        return request;
    }

    /**
     * An LLM stand-in. By default it answers at once; after {@link #holdNextCalls()} every call blocks inside the
     * LLM until {@link #release()}, which freezes a worker thread in the middle of a generation.
     */
    static final class ControllableLlm implements BaseLLMService {

        /** Prefix of every answer; the full answer is this plus " #" and the number of the call. */
        static final String GENERATED = "[TEST LLM] generated care plan";

        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch gate;

        void holdNextCalls() {
            entered = new CountDownLatch(1);
            gate = new CountDownLatch(1);
        }

        boolean hasEntered() {
            return entered.getCount() == 0;
        }

        void release() {
            CountDownLatch current = gate;
            if (current != null) {
                current.countDown();
            }
        }

        /** Back to the default behaviour; also lets any frozen call finish so no thread is left behind. */
        void reset() {
            release();
            gate = null;
            entered = new CountDownLatch(1);
            calls.set(0);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String generateCarePlan(String patientInfo) {
            int callNumber = calls.incrementAndGet();
            CountDownLatch current = gate;
            if (current != null) {
                entered.countDown();
                try {
                    if (!current.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The test never released the LLM");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            // Numbered, so a test can tell the output of a first run from the output of a regeneration.
            return GENERATED + " #" + callNumber;
        }
    }

    /**
     * Spring Retry calls every {@link RetryListener} bean on every failed attempt of every {@code @Retryable}.
     * The tests use it as a precise signal ("attempt 1 of generateWithRetry has failed") instead of a sleep.
     */
    static final class RetryFailureRecorder implements RetryListener {

        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public <T, E extends Throwable> void onError(
                RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            failures.incrementAndGet();
        }

        int failures() {
            return failures.get();
        }

        void reset() {
            failures.set(0);
        }
    }

    /** One care_plans row as PostgreSQL really holds it (committed data only). */
    record CarePlanRow(String status, String content, String errorMessage,
                       Timestamp startedAt, Timestamp finishedAt, Integer attemptCount) {
    }

    /** Reads PostgreSQL through a brand-new connection per call, so only committed data is ever visible. */
    static final class Database {

        private final PostgreSQLContainer postgres;

        Database(PostgreSQLContainer postgres) {
            this.postgres = postgres;
        }

        Optional<CarePlanRow> find(long carePlanId) {
            String sql = "SELECT status, content, error_message, started_at, finished_at, attempt_count "
                    + "FROM care_plans WHERE id = ?";
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, carePlanId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new CarePlanRow(
                            rs.getString("status"), rs.getString("content"), rs.getString("error_message"),
                            rs.getTimestamp("started_at"), rs.getTimestamp("finished_at"),
                            rs.getObject("attempt_count", Integer.class)));
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        /** How many database sessions are currently blocked waiting for a row lock held by someone else. */
        int sessionsWaitingForARowLock() {
            String sql = "SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'";
            try (Connection connection = open();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        private Connection open() throws SQLException {
            return DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    /** Looks at the queue with redis-cli inside the container, bypassing every application class. */
    static final class RedisCli {

        private final GenericContainer<?> redis;

        RedisCli(GenericContainer<?> redis) {
            this.redis = redis;
        }

        long length() {
            return Long.parseLong(run("LLEN", QUEUE_KEY).trim());
        }

        List<String> items() {
            String out = run("LRANGE", QUEUE_KEY, "0", "-1").trim();
            return out.isEmpty() ? List.of() : Arrays.asList(out.split("\\R"));
        }

        void flushAll() {
            run("FLUSHALL");
        }

        private String run(String... args) {
            String[] command = new String[args.length + 1];
            command[0] = "redis-cli";
            System.arraycopy(args, 0, command, 1, args.length);
            try {
                return redis.execInContainer(command).getStdout();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
