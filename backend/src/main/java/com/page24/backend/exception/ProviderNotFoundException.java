package com.page24.backend.exception;

/** Thrown when a Provider resource cannot be found by its ID. */
public class ProviderNotFoundException extends RuntimeException {
    public ProviderNotFoundException() {
        super("No Provider matches the given query.");
    }
}
