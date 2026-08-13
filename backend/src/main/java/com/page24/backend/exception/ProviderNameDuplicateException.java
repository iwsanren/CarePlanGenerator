package com.page24.backend.exception;

public class ProviderNameDuplicateException extends RuntimeException {
    private final Long existingProviderId;
    private final String warning;

    public ProviderNameDuplicateException(String warning, Long existingProviderId) {
        super(warning);
        this.warning = warning;
        this.existingProviderId = existingProviderId;
    }

    public Long getExistingProviderId() {
        return existingProviderId;
    }

    public String getWarning() {
        return warning;
    }
}
