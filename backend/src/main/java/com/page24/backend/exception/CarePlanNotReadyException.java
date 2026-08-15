package com.page24.backend.exception;

/** Thrown when a CarePlan has not finished generating and cannot be downloaded. */
public class CarePlanNotReadyException extends RuntimeException {

    public CarePlanNotReadyException() {
        super("CarePlan not yet generated");
    }
}
