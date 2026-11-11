package com.page24.backend.service;

import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Describes a failure for the server log without repeating anything the exception says.
 *
 * <p>The message of an exception raised by a library or by a provider call can carry the data it was working on: the
 * start of a reply that is not JSON, a value from a database constraint ("Key (mrn)=(123456) already exists"), a
 * fragment of the key. In this system those are patient data or secrets. So the log gets only what cannot hold them:
 * the exception types along the chain of causes, and the HTTP status when a provider answered with one. That is enough
 * to tell a timeout from a 401 from an unreadable reply.
 */
final class FailureDescription {

    // A chain of causes can loop back on itself, and one this long is not going to say anything more.
    private static final int MAX_CHAIN = 10;

    private FailureDescription() {
    }

    static String of(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Integer httpStatus = null;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CHAIN; depth++) {
            if (depth > 0) {
                text.append(" <- ");
            }
            text.append(current.getClass().getName());
            if (httpStatus == null && current instanceof WebClientResponseException response) {
                httpStatus = response.getStatusCode().value();
            }
            current = current.getCause();
        }
        if (httpStatus != null) {
            text.append(" (HTTP ").append(httpStatus).append(')');
        }
        return text.toString();
    }
}
