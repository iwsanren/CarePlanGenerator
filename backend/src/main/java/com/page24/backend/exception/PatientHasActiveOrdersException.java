package com.page24.backend.exception;

import java.util.List;

/** Thrown when deleting a patient would interrupt pending or processing work. */
public class PatientHasActiveOrdersException extends RuntimeException {
    private final List<Long> activeOrderIds;

    public PatientHasActiveOrdersException(List<Long> activeOrderIds) {
        super("Cannot delete patient with active orders");
        this.activeOrderIds = activeOrderIds;
    }

    public List<Long> getActiveOrderIds() {
        return activeOrderIds;
    }
}
