package com.page24.backend.exception;

import org.springframework.http.HttpStatus;

public class ValidationError extends BaseAppException {

    public ValidationError(String code, String message) {
        super("validation", code, message, null, HttpStatus.BAD_REQUEST);
    }

    public ValidationError(String code, String message, Object detail) {
        super("validation", code, message, detail, HttpStatus.BAD_REQUEST);
    }
}

