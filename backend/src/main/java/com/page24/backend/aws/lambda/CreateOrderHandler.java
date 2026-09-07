package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.ApiErrorResponse;
import com.page24.backend.exception.BaseAppException;
import com.page24.backend.service.OrderService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API Gateway Lambda handler for:
 *   POST /orders
 *
 * Handler setting in AWS Lambda:
 *   com.page24.backend.aws.lambda.CreateOrderHandler::handleRequest
 */
@Slf4j
public class CreateOrderHandler {

    private final ObjectMapper objectMapper;
    private final OrderService orderService;
    private final Validator validator;

    public CreateOrderHandler() {
        ConfigurableApplicationContext context = LambdaSpringContext.getContext();
        this.objectMapper = context.getBean(ObjectMapper.class);
        this.orderService = context.getBean(OrderService.class);
        this.validator = context.getBean(Validator.class);
    }

    public Map<String, Object> handleRequest(Map<String, Object> event) {
        try {
            CreateOrderRequest request = parseBody(event);
            validate(request);
            OrderResponse response = orderService.createOrder(request);
            return jsonResponse(HttpStatus.CREATED.value(), response);
        } catch (IllegalArgumentException ex) {
            ApiErrorResponse body = new ApiErrorResponse(
                    "validation",
                    "INVALID_REQUEST_BODY",
                    ex.getMessage(),
                    null,
                    HttpStatus.BAD_REQUEST.value()
            );
            return jsonResponse(HttpStatus.BAD_REQUEST.value(), body);
        } catch (BaseAppException ex) {
            ApiErrorResponse body = new ApiErrorResponse(
                    ex.getType(),
                    ex.getCode(),
                    ex.getMessage(),
                    ex.getDetail(),
                    ex.getHttpStatus().value()
            );
            return jsonResponse(ex.getHttpStatus().value(), body);
        } catch (Exception ex) {
            log.error("Unexpected error while creating order in Lambda", ex);
            ApiErrorResponse body = new ApiErrorResponse(
                    "error",
                    "INTERNAL_SERVER_ERROR",
                    "Unexpected server error",
                    Map.of("exception", ex.getClass().getSimpleName()),
                    HttpStatus.INTERNAL_SERVER_ERROR.value()
            );
            return jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), body);
        }
    }

    private CreateOrderRequest parseBody(Map<String, Object> event) {
        Object bodyObject = event.get("body");
        if (bodyObject == null || bodyObject.toString().isBlank()) {
            throw new IllegalArgumentException("Request body is required");
        }

        String body = bodyObject.toString();
        if (Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            return toCreateOrderRequest(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Malformed or unreadable JSON request body");
        }
    }

    private CreateOrderRequest toCreateOrderRequest(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }

        CreateOrderRequest request = new CreateOrderRequest();
        request.setPatientFirstName(text(root, "patientFirstName"));
        request.setPatientLastName(text(root, "patientLastName"));
        request.setPatientMrn(text(root, "patientMrn"));
        request.setPatientDateOfBirth(localDate(root, "patientDateOfBirth"));
        request.setProviderName(text(root, "providerName"));
        request.setProviderNpi(text(root, "providerNpi"));
        request.setMedicationName(text(root, "medicationName"));
        request.setPrimaryDiagnosis(text(root, "primaryDiagnosis"));
        request.setAdditionalDiagnoses(stringList(root, "additionalDiagnoses"));
        request.setMedicationHistory(stringList(root, "medicationHistory"));
        request.setPatientRecords(text(root, "patientRecords"));
        request.setConfirm(booleanValue(root, "confirm"));
        return request;
    }

    private List<String> stringList(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(fieldName + " must contain only strings");
            }
            result.add(item.asText());
        }
        return result;
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return value.asText();
    }

    private LocalDate localDate(JsonNode root, String fieldName) {
        String value = text(root, fieldName);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(fieldName + " must use yyyy-MM-dd format");
        }
    }

    private Boolean booleanValue(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(fieldName + " must be a boolean");
        }
        return value.asBoolean();
    }

    private void validate(CreateOrderRequest request) {
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return;
        }

        Map<String, String> detail = new LinkedHashMap<>();
        for (ConstraintViolation<CreateOrderRequest> violation : violations) {
            detail.put(violation.getPropertyPath().toString(), violation.getMessage());
        }

        throw new com.page24.backend.exception.ValidationError(
                "INVALID_REQUEST_BODY",
                "Request body validation failed",
                detail
        );
    }

    private Map<String, Object> jsonResponse(int statusCode, Object body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", statusCode);
        response.put("headers", headers);
        response.put("body", toJson(body));
        return response;
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return "{\"type\":\"error\",\"code\":\"JSON_SERIALIZATION_FAILED\",\"message\":\"Failed to serialize response\",\"httpStatus\":500}";
        }
    }
}
