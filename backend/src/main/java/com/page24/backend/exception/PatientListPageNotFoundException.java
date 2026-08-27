package com.page24.backend.exception;

/** Matches Django REST Framework's response for an invalid page-number request. */
public class PatientListPageNotFoundException extends RuntimeException {

    public PatientListPageNotFoundException() {
        super("Invalid page.");
    }
}
