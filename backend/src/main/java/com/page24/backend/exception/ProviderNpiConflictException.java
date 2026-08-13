package com.page24.backend.exception;

public class ProviderNpiConflictException extends RuntimeException {
    private final Long existingProviderId;

    public ProviderNpiConflictException(String message, Long existingProviderId) {
        super(message);
        this.existingProviderId = existingProviderId;
    }

    public Long getExistingProviderId() {
        return existingProviderId;
    }
}
