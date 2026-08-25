package com.page24.backend.exception;

/** Thrown when a requested patient MRN does not exist. */
public class PatientMrnNotFoundException extends RuntimeException {
    public PatientMrnNotFoundException() {
        super("Patient not found");
    }
}
