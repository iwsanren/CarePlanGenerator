package com.page24.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The conditional statements through which the Kafka path changes a care plan. Each names the state it expects and
 * changes only the columns it means to change; "0 rows" means the situation is not what the caller thought. All
 * times are the database clock, so several instances cannot disagree about whether a lease has expired.
 * PostgreSQL only (RETURNING).
 */
@Repository
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@RequiredArgsConstructor
public class CarePlanClaimRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Takes the plan for generation if it is PENDING, or if it is PROCESSING and may be taken over: by the same
     * message again, or because the lease has run out. A plan is taken over at most {@code maxReclaims} times.
     *
     * @return the new claim token, or empty if the plan may not be claimed
     */
    public OptionalInt claim(long carePlanId, UUID eventId, Duration lease, int maxReclaims) {
        List<Integer> tokens = jdbcTemplate.queryForList("""
                UPDATE care_plans
                   SET status = 'PROCESSING',
                       claim_token = claim_token + 1,
                       claimed_event_id = ?,
                       lease_expires_at = now() + (? * INTERVAL '1 millisecond'),
                       reclaim_count = CASE WHEN status = 'PENDING' THEN 0 ELSE reclaim_count + 1 END,
                       started_at = now(), finished_at = NULL, attempt_count = NULL, updated_at = now()
                 WHERE id = ?
                   AND (status = 'PENDING'
                        OR (status = 'PROCESSING' AND reclaim_count < ?
                            AND (claimed_event_id = ? OR lease_expires_at IS NULL OR lease_expires_at < now())))
                RETURNING claim_token""",
                Integer.class, eventId, lease.toMillis(), carePlanId, maxReclaims, eventId);
        return tokens.isEmpty() ? OptionalInt.empty() : OptionalInt.of(tokens.get(0));
    }

    /** Why a claim failed. Used only to decide what to do with the message; nothing is written from it. */
    public ClaimState stateOf(long carePlanId, UUID eventId) {
        List<ClaimState> states = jdbcTemplate.query("""
                        SELECT status, reclaim_count,
                               (claimed_event_id = ? OR lease_expires_at IS NULL OR lease_expires_at < now()) AS open
                          FROM care_plans WHERE id = ?""",
                (rs, rowNumber) -> new ClaimState(
                        rs.getString("status"), rs.getInt("reclaim_count"), rs.getBoolean("open")),
                eventId, carePlanId);
        return states.isEmpty() ? null : states.get(0);
    }

    /** Writes the generated content, only for the current owner. */
    public boolean complete(long carePlanId, int claimToken, String content, int attempts) {
        return jdbcTemplate.update("""
                UPDATE care_plans
                   SET status = 'COMPLETED', content = ?, error_message = NULL,
                       finished_at = now(), attempt_count = ?, lease_expires_at = NULL, updated_at = now()
                 WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?""",
                content, attempts, carePlanId, claimToken) == 1;
    }

    /** Records that the generation gave up, only for the current owner. */
    public boolean fail(long carePlanId, int claimToken, String errorMessage, int attempts) {
        return jdbcTemplate.update("""
                UPDATE care_plans
                   SET status = 'FAILED', error_message = ?,
                       finished_at = now(), attempt_count = ?, lease_expires_at = NULL, updated_at = now()
                 WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?""",
                errorMessage, attempts, carePlanId, claimToken) == 1;
    }

    /**
     * Gives the plan up for good because its message goes to the dead-letter topic: only if nobody else is working on
     * it (it is PENDING, was claimed by this message, or its lease has run out).
     */
    public boolean failAbandoned(long carePlanId, UUID eventId, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE care_plans
                   SET status = 'FAILED', error_message = ?, finished_at = now(), lease_expires_at = NULL,
                       claim_token = claim_token + 1, updated_at = now()
                 WHERE id = ?
                   AND (status = 'PENDING'
                        OR (status = 'PROCESSING'
                            AND (claimed_event_id = ? OR lease_expires_at IS NULL OR lease_expires_at < now())))""",
                errorMessage, carePlanId, eventId) == 1;
    }

    /**
     * PROCESSING plans whose lease has run out (or that never had one) and for which no message has been written
     * since then. Locked until the caller's transaction ends; rows another sweeper holds are skipped.
     */
    public List<ExpiredClaim> lockExpired(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, reclaim_count
                          FROM care_plans
                         WHERE status = 'PROCESSING'
                           AND (lease_expires_at IS NULL OR lease_expires_at < now())
                           AND NOT EXISTS (SELECT 1 FROM outbox_event o
                                            WHERE o.aggregate_id = care_plans.id
                                              AND o.created_at >= care_plans.lease_expires_at)
                         ORDER BY id
                         LIMIT ?
                           FOR UPDATE OF care_plans SKIP LOCKED""",
                (rs, rowNumber) -> new ExpiredClaim(rs.getLong("id"), rs.getInt("reclaim_count")),
                limit);
    }

    /**
     * Gives a PROCESSING plan without a lease (left by the Redis worker) a lease that has just run out. The sweeper
     * calls this in the transaction that writes the new message, so that the next scan can see that a message was
     * written "since the lease ran out" instead of writing one on every scan.
     */
    public void expireLeaseNowIfMissing(long carePlanId) {
        jdbcTemplate.update("""
                UPDATE care_plans SET lease_expires_at = now()
                 WHERE id = ? AND status = 'PROCESSING' AND lease_expires_at IS NULL""", carePlanId);
    }

    /** Status, take-over count, and whether this message could take the plan over if the count allowed it. */
    public record ClaimState(String status, int reclaimCount, boolean open) {
    }

    /** A plan the sweeper found. */
    public record ExpiredClaim(long carePlanId, int reclaimCount) {
    }
}
