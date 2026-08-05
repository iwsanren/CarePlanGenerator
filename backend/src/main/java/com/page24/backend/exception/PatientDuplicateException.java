package com.page24.backend.exception;

public class PatientDuplicateException extends RuntimeException {
    private final Long existingPatientId;
    private final String warning;

    public PatientDuplicateException(String warning, Long existingPatientId) {
        super(warning);
        this.warning = warning;
        this.existingPatientId = existingPatientId;
    }

    public Long getExistingPatientId() {
        return existingPatientId;
    }

    public String getWarning() {
        return warning;
    }
}
