package com.page24.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for all app-level exceptions that should return unified JSON.
 */
public abstract class BaseAppException extends RuntimeException {

    private final String type;
    private final String code;
    private final Object detail;
    private final HttpStatus httpStatus;

    protected BaseAppException(String type, String code, String message, Object detail, HttpStatus httpStatus) {
        super(message);
        this.type = type;
        this.code = code;
        this.detail = detail;
        this.httpStatus = httpStatus;
    }

    public String getType() {
        return type;
    }

    public String getCode() {
        return code;
    }

    public Object getDetail() {
        return detail;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}

