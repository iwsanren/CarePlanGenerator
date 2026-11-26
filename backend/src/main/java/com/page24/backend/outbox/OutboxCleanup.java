package com.page24.backend.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Removes rows that were sent long ago, in small batches so that no statement holds locks for long. A row that has
 * not been sent is never removed, however old it is: it is something to look into, not rubbish.
 */
@Component
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@Slf4j
public class OutboxCleanup {

    static final int BATCH_SIZE = 1000;

    private final OutboxRepository outbox;
    private final Duration retention;

    public OutboxCleanup(OutboxRepository outbox, @Value("${careplan.outbox.retention}") Duration retention) {
        this.outbox = outbox;
        this.retention = retention;
    }

    @Scheduled(cron = "${careplan.outbox.cleanup-cron}")
    public void removeOldSentRows() {
        long removed = 0;
        int deleted;
        do {
            deleted = outbox.deleteSentOlderThan(retention, BATCH_SIZE);
            removed += deleted;
        } while (deleted == BATCH_SIZE);
        if (removed > 0) {
            log.info("Outbox: removed {} rows sent more than {} days ago", removed, retention.toDays());
        }
    }
}
