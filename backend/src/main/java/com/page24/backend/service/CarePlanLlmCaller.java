package com.page24.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * The only part of the Kafka path that is retried by Spring Retry: the LLM call, with the same three attempts and
 * 2 s / 4 s back-off as the Redis path. It touches no database, so a failed database write never calls the LLM again
 * from here and is never mistaken for the LLM giving up. A separate bean, because Spring Retry works through the
 * proxy.
 */
@Service
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class CarePlanLlmCaller {

    private final LLMAdapterFactory llmAdapterFactory;

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 2))
    public Outcome generate(long carePlanId, String patientInfo) {
        log.info("Calling the LLM (carePlanId={})", carePlanId);
        String content = llmAdapterFactory.getService().generateCarePlan(patientInfo);
        return new Outcome(content, RetryAttempts.current());
    }

    /** All attempts failed: a business result, not an error to retry elsewhere. */
    @Recover
    public Outcome giveUp(Exception e, long carePlanId, String patientInfo) {
        log.error("The LLM failed on every attempt (carePlanId={}): {}", carePlanId, FailureDescription.of(e));
        return new Outcome(null, RetryAttempts.exhausted());
    }

    /** @param content the generated care plan, or null if the LLM gave up */
    public record Outcome(String content, int attempts) {

        boolean gaveUp() {
            return content == null;
        }
    }
}
