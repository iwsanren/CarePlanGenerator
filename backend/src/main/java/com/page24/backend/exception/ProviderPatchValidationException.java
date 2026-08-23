package com.page24.backend.exception;

/** Thrown when a supplied PATCH field is not allowed to be null. */
public class ProviderPatchValidationException extends RuntimeException {

    private final String field;

    public ProviderPatchValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
