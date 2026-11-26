package com.page24.backend.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Plain SQL on the outbox_event table. Every method takes part in whatever transaction the caller has open. */
@Repository
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@RequiredArgsConstructor
public class OutboxRepository {

    private static final int MAX_ERROR_LENGTH = 200;

    private final JdbcTemplate jdbcTemplate;

    public void insert(UUID eventId, long aggregateId, String topic, String messageKey, String payload) {
        jdbcTemplate.update("""
                INSERT INTO outbox_event (event_id, aggregate_id, topic, message_key, payload)
                VALUES (?, ?, ?, ?, ?)""", eventId, aggregateId, topic, messageKey, payload);
    }

    /**
     * Takes up to {@code limit} unsent rows and locks them until the caller's transaction ends. Rows another sender
     * has locked are skipped, not waited for. Rows that have failed come after those that have not, so a row that can
     * never be sent does not stand in front of the others.
     */
    public List<OutboxRow> lockUnsent(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, event_id, aggregate_id, topic, message_key, payload
                          FROM outbox_event
                         WHERE sent_at IS NULL
                         ORDER BY attempts, id
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED""",
                (rs, rowNumber) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getLong("aggregate_id"),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("payload")),
                limit);
    }

    public void markSent(long id) {
        jdbcTemplate.update("UPDATE outbox_event SET sent_at = now() WHERE id = ? AND sent_at IS NULL", id);
    }

    public void markFailed(long id, String errorName) {
        String shortened = errorName.length() > MAX_ERROR_LENGTH
                ? errorName.substring(0, MAX_ERROR_LENGTH)
                : errorName;
        jdbcTemplate.update("""
                UPDATE outbox_event SET attempts = attempts + 1, last_error = ?
                 WHERE id = ? AND sent_at IS NULL""", shortened, id);
    }

    public long countUnsent() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE sent_at IS NULL", Long.class);
        return count != null ? count : 0L;
    }

    /** Deletes at most {@code limit} rows sent longer than {@code retention} ago. Unsent rows are never deleted. */
    public int deleteSentOlderThan(Duration retention, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM outbox_event
                 WHERE id IN (SELECT id FROM outbox_event
                               WHERE sent_at < now() - (? * INTERVAL '1 second')
                               ORDER BY id
                               LIMIT ?)""", retention.toSeconds(), limit);
    }
}
