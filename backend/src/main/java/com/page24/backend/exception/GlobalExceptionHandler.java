package com.page24.backend.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.ClassUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Method authorization can fail inside MVC, after the filter chain's URL checks.
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ApiErrorResponse("error", "ACCESS_DENIED", "Permission denied", null, 403));
    }

    @ExceptionHandler(CarePlanNotReadyException.class)
    public ResponseEntity<Map<String, String>> handleCarePlanNotReady(CarePlanNotReadyException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(BaseAppException.class)
    public ResponseEntity<ApiErrorResponse> handleBaseAppException(BaseAppException ex) {
        ApiErrorResponse body = new ApiErrorResponse(
                ex.getType(),
                ex.getCode(),
                ex.getMessage(),
                ex.getDetail(),
                ex.getHttpStatus().value()
        );
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> detail = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            detail.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ApiErrorResponse body = new ApiErrorResponse(
                "validation",
                "INVALID_REQUEST_BODY",
                "Request body validation failed",
                detail,
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> detail = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            detail.put(violation.getPropertyPath().toString(), violation.getMessage());
        }

        ApiErrorResponse body = new ApiErrorResponse(
                "validation",
                "INVALID_REQUEST_PARAM",
                "Request parameter validation failed",
                detail,
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        ApiErrorResponse body = new ApiErrorResponse(
                "validation",
                "MALFORMED_JSON",
                "Malformed or unreadable JSON request body",
                ex.getMostSpecificCause().getMessage(),
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Mistakes made by the caller that Spring MVC already knows how to classify: a method the path does not support
     * (405), an unsupported or unacceptable media type (415, 406), a missing parameter or part (400), a path that does
     * not exist (404) and an upload that is too large (413). Each of these exceptions carries its own status and
     * headers (Allow, Accept), which are used as they are. Without this handler the catch-all below turns all of them
     * into a 500. The messages are fixed on purpose, so they never repeat what the caller sent.
     */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            NoResourceFoundException.class,
            MaxUploadSizeExceededException.class
    })
    public ResponseEntity<ApiErrorResponse> handleStandardHttpError(Exception ex) {
        ErrorResponse errorResponse = (ErrorResponse) ex;
        int status = errorResponse.getStatusCode().value();
        log.debug("Rejected request with {}: {}", status, ex.getClass().getSimpleName());

        ApiErrorResponse body = status == HttpStatus.BAD_REQUEST.value()
                ? new ApiErrorResponse("validation", "INVALID_REQUEST_PARAM",
                        "Request parameter validation failed", missingValueDetail(ex), status)
                : new ApiErrorResponse("error", errorCode(status), errorMessage(status), null, status);
        return ResponseEntity.status(errorResponse.getStatusCode())
                .headers(errorResponse.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** A path or query value that cannot be converted, such as "abc" where a number is expected. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.debug("Rejected request: parameter '{}' has the wrong type", ex.getName());
        Class<?> requiredType = ex.getRequiredType();
        boolean number = requiredType != null
                && Number.class.isAssignableFrom(ClassUtils.resolvePrimitiveIfNecessary(requiredType));
        ApiErrorResponse body = new ApiErrorResponse(
                "validation",
                "INVALID_REQUEST_PARAM",
                "Request parameter validation failed",
                Map.of(ex.getName(), number ? "must be a number" : "has an invalid value"),
                HttpStatus.BAD_REQUEST.value()
        );
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static Map<String, String> missingValueDetail(Exception ex) {
        if (ex instanceof MissingServletRequestParameterException missingParameter) {
            return Map.of(missingParameter.getParameterName(), "is required");
        }
        if (ex instanceof MissingServletRequestPartException missingPart) {
            return Map.of(missingPart.getRequestPartName(), "is required");
        }
        return null;
    }

    private static String errorCode(int status) {
        return switch (status) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "BAD_REQUEST";
        };
    }

    private static String errorMessage(int status) {
        return switch (status) {
            case 404 -> "Resource not found";
            case 405 -> "Method not allowed";
            case 406 -> "Not acceptable";
            case 413 -> "Payload too large";
            case 415 -> "Unsupported media type";
            default -> "Bad request";
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unexpected server error", ex);
        ApiErrorResponse body = new ApiErrorResponse(
                "error",
                "INTERNAL_SERVER_ERROR",
                "Unexpected server error",
                null,
                HttpStatus.INTERNAL_SERVER_ERROR.value()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}


