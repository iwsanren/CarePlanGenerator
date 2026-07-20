package com.page24.backend.service;

/**
 * Queue abstraction for "please generate this CarePlan later".
 *
 * Local profile: Redis implementation.
 * Lambda profile: SQS implementation.
 */
public interface CarePlanQueue {

    void enqueue(Long carePlanId);
}
