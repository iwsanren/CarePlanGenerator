package com.page24.backend.service;

/**
 * A care plan was claimed as often as allowed and still not finished. Retrying would only pay for more generations,
 * so the message goes to the dead-letter topic without being retried.
 */
public class ClaimLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ClaimLimitExceededException(long carePlanId) {
        super("Claim limit reached for carePlanId=" + carePlanId);
    }
}
