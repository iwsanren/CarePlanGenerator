package com.page24.backend.intake;

import com.page24.backend.exception.BaseAppException;
import org.springframework.http.HttpStatus;

/**
 * Raised when InternalOrder fails structural/format validation.
 */
public class IntakeValidationException extends BaseAppException {

    public IntakeValidationException(String code, String message) {
        super("validation", code, message, null, HttpStatus.BAD_REQUEST);
    }

    public IntakeValidationException(String code, String message, Object detail) {
        super("validation", code, message, detail, HttpStatus.BAD_REQUEST);
    }
}

