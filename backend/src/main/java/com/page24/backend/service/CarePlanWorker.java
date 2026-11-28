package com.page24.backend.service;

import com.page24.backend.repository.CarePlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * Care Plan worker. Two ways it gets triggered:
 *
 * 1. onCarePlanQueued — event-driven, near-instant pickup. Fires after the
 *    enqueuing transaction commits (so the CarePlan row is guaranteed to be
 *    visible), and on a dedicated thread (so it never blocks the HTTP
 *    request that created the order).
 * 2. pollQueue — a 30s backstop. Under normal operation the queue is already
 *    empty by the time this runs; it only does real work if an event was
 *    missed (e.g. app restart with tasks left over in Redis from before).
 *
 * Both paths funnel into drainQueue(), which processes every task currently
 * sitting in the queue (not just one) — Redis's leftPop is atomic, so it is
 * safe for the event trigger and the poll trigger to call drainQueue()
 * concurrently without double-processing the same task.
 */
@Service
@Profile("!lambda")
// Both must hold: the worker is not switched off, and the queue is the Redis one it reads from.
@ConditionalOnProperty(name = "careplan.worker.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CarePlanWorker {

    private final QueueService queueService;
    private final CarePlanRepository carePlanRepository;
    private final CarePlanGenerationService carePlanGenerationService;

    @Async("carePlanTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCarePlanQueued(CarePlanQueuedEvent event) {
        log.info("⚡ Event-triggered drain (carePlanId={})", event.carePlanId());
        drainQueue();
    }

    @Scheduled(fixedDelay = 30000)
    public void pollQueue() {
        drainQueue();
    }

    private void drainQueue() {
        Long carePlanId;
        while ((carePlanId = queueService.dequeue()) != null) {
            processTask(carePlanId);
        }
    }

    private void processTask(Long carePlanId) {
        // Queue messages contain only an ID; execution never borrows an employee token.
        log.info("System worker picked up task: carePlanId={}", carePlanId);

        carePlanRepository.findById(carePlanId).ifPresent(carePlan -> {
            // Also stamps started_at and clears the result of any earlier run.
            carePlan.markProcessing(LocalDateTime.now());
            carePlanRepository.save(carePlan);
        });
        log.info("⚙️  Status updated to PROCESSING: carePlanId={}", carePlanId);

        // @Retryable on this method handles transient failure retries.
        carePlanGenerationService.generateWithRetry(carePlanId);
    }
}
