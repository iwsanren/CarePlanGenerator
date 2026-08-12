package com.page24.backend.exception;

/** Thrown when a requested patient ID does not exist. */
public class PatientNotFoundException extends RuntimeException {
    public PatientNotFoundException() {
        super("Patient not found");
    }
}
