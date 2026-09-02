package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Care Plan worker that pulls tasks from the Redis queue.
 *
 * Responsibilities, analogous to a Celery setup:
 *
 *   CarePlanWorker            - equivalent to a Celery worker process that pulls tasks.
 *   CarePlanGenerationService - equivalent to a Celery task function that processes and retries tasks.
 *
 * The worker has two responsibilities:
 * 1. Pull one task from Redis every five seconds.
 * 2. Delegate it to CarePlanGenerationService, which handles the LLM call and retries.
 */
@Service
@Profile("!lambda")
@RequiredArgsConstructor
@Slf4j
public class CarePlanWorker {

    private final QueueService queueService;
    private final CarePlanRepository carePlanRepository;
    private final CarePlanGenerationService carePlanGenerationService;

    /**
     * Runs every five seconds and processes one task from the queue.
     *
     * With fixedDelay = 5000, the next invocation begins five seconds after the
     * previous one finishes, rather than on a fixed five-second schedule.
     */
    @Scheduled(fixedDelay = 5000)
    public void processNextTask() {

        // Step 1: Pull a task from Redis and return when the queue is empty.
        Long carePlanId = queueService.dequeue();
        if (carePlanId == null) {
            return;
        }

        log.info("🔄 Worker 拿到任务: carePlanId={}", carePlanId);

        // Step 2: Mark the CarePlan as PROCESSING before generation begins.
        carePlanRepository.findById(carePlanId).ifPresent(carePlan -> {
            carePlan.setStatus(CarePlan.Status.PROCESSING);
            carePlanRepository.save(carePlan);
        });
        log.info("⚙️  状态改为 PROCESSING: carePlanId={}", carePlanId);

        // Step 3: Delegate processing to GenerationService.
        // Its @Retryable annotation handles retries for failures.
        carePlanGenerationService.generateWithRetry(carePlanId);

        // The frontend is not notified when processing finishes.
        // The user must refresh manually to see the COMPLETED status.
        // This intentional limitation is addressed with polling on the next day.
    }
}
