package com.page24.backend.service;

/**
 * A message that can never be processed: it goes to the dead-letter topic without being retried. The text says what
 * is wrong, never what the message contained.
 */
public class InvalidEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEventException(String what) {
        super("Invalid care plan event: " + what);
    }
}
