package com.page24.backend.exception;

/** Thrown when a patient update attempts to include the immutable MRN field. */
public class PatientMrnModificationException extends RuntimeException {
    public PatientMrnModificationException() {
        super("MRN cannot be modified");
    }
}
