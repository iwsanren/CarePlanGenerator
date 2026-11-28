package com.page24.backend.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends outbox rows to Kafka. Delivery is at least once.
 *
 * One batch is one database transaction: lock up to {@code batch-size} unsent rows, send them one at a time waiting
 * for the broker's acknowledgement, and mark each acknowledged row as sent. At the first failure the batch stops, so
 * that an unreachable broker costs one timeout and not one per locked row; the failed row's attempt count goes up,
 * which puts it behind the others next time, and the untried rows are left as they were.
 *
 * A row is sent twice if the broker stored it but the acknowledgement was lost or came too late, or if this process
 * or the database failed before {@code sent_at} was committed. Consumers must cope with duplicates.
 *
 * The producer must give up on a message before this class stops waiting for it (delivery.timeout.ms below
 * send-timeout); otherwise a row recorded as failed could still be delivered later and then be sent again.
 */
@Component
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@Slf4j
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final Duration sendTimeout;
    private final SendFailureLog failureLog = new SendFailureLog(Duration.ofSeconds(60), System::nanoTime);

    public OutboxRelay(OutboxRepository outbox,
                       KafkaTemplate<String, String> kafka,
                       PlatformTransactionManager transactionManager,
                       @Value("${careplan.outbox.batch-size}") int batchSize,
                       @Value("${careplan.outbox.send-timeout}") Duration sendTimeout) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /** The safety net; normally a commit wakes the sender at once (see OutboxWakeUpListener). */
    @Scheduled(fixedDelayString = "${careplan.outbox.poll-interval}")
    public void poll() {
        drain();
    }

    /** Sends batch after batch until the outbox is empty or a send fails. Never throws. */
    public void drain() {
        try {
            boolean more = true;
            while (more) {
                more = Boolean.TRUE.equals(transaction.execute(status -> sendBatch()));
            }
        } catch (RuntimeException e) {
            // The database, not Kafka: nothing was marked, the next run tries again.
            failureLog.failed(e.getClass().getSimpleName());
        }
    }

    /** @return true if a full batch was sent, so that more rows may be waiting */
    private boolean sendBatch() {
        List<OutboxRow> rows = outbox.lockUnsent(batchSize);
        for (OutboxRow row : rows) {
            try {
                kafka.send(row.topic(), row.messageKey(), row.payload())
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                String errorName = rootCauseName(e);
                outbox.markFailed(row.id(), errorName);
                failureLog.failed(errorName);
                log.debug("Outbox: row not sent, eventId={} carePlanId={}", row.eventId(), row.aggregateId());
                return false;
            }
            outbox.markSent(row.id());
            log.info("Outbox: sent eventId={} carePlanId={}", row.eventId(), row.aggregateId());
        }
        if (!rows.isEmpty()) {
            failureLog.succeeded();
        }
        return rows.size() == batchSize;
    }

    private static String rootCauseName(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
