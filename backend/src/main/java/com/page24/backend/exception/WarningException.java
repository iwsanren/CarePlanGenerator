package com.page24.backend.exception;

import org.springframework.http.HttpStatus;

public class WarningException extends BaseAppException {

    public WarningException(String code, String message, Object detail) {
        super("warning", code, message, detail, HttpStatus.OK);
    }
}

