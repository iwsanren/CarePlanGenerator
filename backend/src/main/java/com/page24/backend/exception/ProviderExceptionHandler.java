package com.page24.backend.exception;

import com.page24.backend.controller.ProviderController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = ProviderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProviderExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationError(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Validation failed", "details", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Validation failed",
                "details", Map.of("body", "Malformed or unreadable JSON request body")
        ));
    }

    @ExceptionHandler(ProviderNameDuplicateException.class)
    public ResponseEntity<Map<String, Object>> handleNameDuplicate(ProviderNameDuplicateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "warning", ex.getWarning(),
                "existing_provider_id", ex.getExistingProviderId()
        ));
    }

    @ExceptionHandler(ProviderNpiConflictException.class)
    public ResponseEntity<Map<String, Object>> handleNpiConflict(ProviderNpiConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", ex.getMessage(),
                "existing_provider_id", ex.getExistingProviderId()
        ));
    }

    @ExceptionHandler(ProviderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleProviderNotFound(ProviderNotFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "not_found");
        body.put("message", ex.getMessage());
        body.put("details", null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(ProviderNpiNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleProviderNpiNotFound(ProviderNpiNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", ex.getMessage()));
    }

    @ExceptionHandler(ProviderPatchValidationException.class)
    public ResponseEntity<Map<String, Object>> handlePatchValidation(ProviderPatchValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Validation failed",
                "details", Map.of(ex.getField(), ex.getMessage())
        ));
    }
}
