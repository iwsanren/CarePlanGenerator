package com.page24.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed queue service.
 *
 * Day 4 learning objectives:
 * 1. Redis is a storage layer for tasks awaiting processing.
 * 2. This service only enqueues tasks; it does not process them.
 * 3. Task processing is introduced with the worker on Day 5.
 */
@Service
@Profile("!lambda")
@RequiredArgsConstructor
@Slf4j
public class QueueService implements CarePlanQueue {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis list key used for queued CarePlan IDs.
    private static final String QUEUE_NAME = "careplan:queue";

    /**
     * Adds a CarePlan ID to the queue.
     *
     * @param carePlanId the CarePlan ID to enqueue
     */
    public void enqueue(Long carePlanId) {
        log.info("📥 Enqueuing Care Plan: carePlanId={}", carePlanId);

        // Append the ID to the tail of the Redis list.
        redisTemplate.opsForList().rightPush(QUEUE_NAME, carePlanId.toString());

        log.info("✅ Care Plan enqueued; current queue size: {}", getQueueSize());
    }

    /**
     * Removes one task from the head of the queue.
     *
     * This method is introduced for the Day 5 worker and is not used on Day 4.
     *
     * @return the CarePlan ID, or null when the queue is empty
     */
    public Long dequeue() {
        String carePlanId = (String) redisTemplate.opsForList().leftPop(QUEUE_NAME);
        if (carePlanId != null) {
            log.info("📤 Dequeued Care Plan: carePlanId={}", carePlanId);
            return Long.parseLong(carePlanId);
        }
        return null;
    }

    /**
     * Returns the current queue length.
     */
    public Long getQueueSize() {
        Long size = redisTemplate.opsForList().size(QUEUE_NAME);
        return size != null ? size : 0L;
    }

    /**
     * Returns all queued tasks without removing them.
     */
    public java.util.List<Object> viewQueue() {
        Long size = getQueueSize();
        if (size == 0) {
            return java.util.Collections.emptyList();
        }
        return redisTemplate.opsForList().range(QUEUE_NAME, 0, -1);
    }
}

