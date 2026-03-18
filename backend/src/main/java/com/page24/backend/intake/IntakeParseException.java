package com.page24.backend.intake;

import com.page24.backend.exception.BaseAppException;
import org.springframework.http.HttpStatus;

/**
 * Raised when raw source payload cannot be parsed.
 */
public class IntakeParseException extends BaseAppException {

    public IntakeParseException(String code, String message) {
        super("validation", code, message, null, HttpStatus.BAD_REQUEST);
    }

    public IntakeParseException(String code, String message, Object detail) {
        super("validation", code, message, detail, HttpStatus.BAD_REQUEST);
    }
}

