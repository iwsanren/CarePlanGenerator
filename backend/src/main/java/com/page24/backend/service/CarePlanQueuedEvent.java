package com.page24.backend.service;

/** Published locally right after a CarePlan id is pushed onto the Redis queue. */
public record CarePlanQueuedEvent(Long carePlanId) {
}
