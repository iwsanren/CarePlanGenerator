package com.page24.backend.outbox;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs the sender right after a commit that wrote an outbox row, so a message normally leaves within milliseconds.
 *
 * It has its own single thread with room for one waiting wake-up; any further wake-up is dropped, because the one
 * waiting will find every row committed so far. Handing the work over can therefore never fail or block the request
 * that has just committed. A lost wake-up costs nothing but the next poll.
 */
@Component
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@RequiredArgsConstructor
public class OutboxWakeUpListener {

    private final OutboxRelay relay;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            task -> new Thread(task, "outbox-wake-up"),
            new ThreadPoolExecutor.DiscardPolicy());

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxWritten(OutboxWritten event) {
        executor.execute(relay::drain);
    }

    @PreDestroy
    void shutDown() {
        executor.shutdownNow();
    }
}
