package com.page24.backend.service;

/**
 * A technical failure (database unavailable and the like): the message is retried. It deliberately has no cause and
 * carries only exception class names, because Spring logs what a listener throws and copies it into dead-letter
 * headers, and a database or library message can contain patient data.
 */
public class TechnicalProcessingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TechnicalProcessingException(String exceptionTypes) {
        super("Care plan processing failed: " + exceptionTypes);
    }
}
