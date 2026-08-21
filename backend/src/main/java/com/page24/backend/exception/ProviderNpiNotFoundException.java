package com.page24.backend.exception;

/** Thrown when no Provider has the requested NPI. */
public class ProviderNpiNotFoundException extends RuntimeException {
    public ProviderNpiNotFoundException() {
        super("Provider not found");
    }
}
