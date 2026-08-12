package com.page24.backend.exception;

import com.page24.backend.controller.PatientController;
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

@RestControllerAdvice(assignableTypes = PatientController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PatientExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationError(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.put(toApiFieldName(fieldError.getField()), fieldError.getDefaultMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed");
        body.put("details", details);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("body", "Malformed or unreadable JSON request body");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed");
        body.put("details", details);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(PatientDuplicateException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicatePatient(PatientDuplicateException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("warning", ex.getWarning());
        body.put("existing_patient_id", ex.getExistingPatientId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private String toApiFieldName(String fieldName) {
        return fieldName.replace("dateOfBirth", "date_of_birth")
                .replace("weightKg", "weight_kg")
                .replace("primaryDiagnosis", "primary_diagnosis")
                .replace("additionalDiagnoses", "additional_diagnoses")
                .replace("firstName", "first_name")
                .replace("lastName", "last_name");
    }
}
