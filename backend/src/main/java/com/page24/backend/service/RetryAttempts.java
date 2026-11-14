package com.page24.backend.service;

import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;

/**
 * Reads the attempt number from the Spring Retry context of the current thread.
 *
 * The context is created per call of a @Retryable method, so it is safe with concurrent workers (unlike a
 * counter field on the singleton service). RetryContext.getRetryCount() is the number of attempts that have
 * already failed in this call. Outside a retry (a direct call, as in plain unit tests) there is no context and
 * the call counts as a single attempt.
 */
final class RetryAttempts {

    private RetryAttempts() {
    }

    /** Number of the attempt that is running now: 1 on the first call, 2 after one failure, ... */
    static int current() {
        RetryContext context = RetrySynchronizationManager.getContext();
        return context == null ? 1 : context.getRetryCount() + 1;
    }

    /** Number of attempts that were made, read from the recovery callback after the last one failed. */
    static int exhausted() {
        RetryContext context = RetrySynchronizationManager.getContext();
        return context == null ? 1 : context.getRetryCount();
    }
}
