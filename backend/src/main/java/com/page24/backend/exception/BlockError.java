package com.page24.backend.exception;

import org.springframework.http.HttpStatus;

public class BlockError extends BaseAppException {

    public BlockError(String code, String message) {
        super("block", code, message, null, HttpStatus.CONFLICT);
    }

    public BlockError(String code, String message, Object detail) {
        super("block", code, message, detail, HttpStatus.CONFLICT);
    }
}

