package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.ApiErrorResponse;
import com.page24.backend.exception.BaseAppException;
import com.page24.backend.exception.ValidationError;
import com.page24.backend.service.OrderService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API Gateway Lambda handler for:
 *   GET /orders/{id}
 *
 * Handler setting in AWS Lambda:
 *   com.page24.backend.aws.lambda.GetOrderHandler::handleRequest
 */
@Slf4j
public class GetOrderHandler {

    // findAndRegisterModules() adds the Java time module, needed to serialize LocalDate fields.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OrderService orderService;

    public GetOrderHandler() {
        this(LambdaSpringContext.getContext().getBean(OrderService.class));
    }

    GetOrderHandler(OrderService orderService) {
        this.orderService = orderService;
    }

    public Map<String, Object> handleRequest(Map<String, Object> event) {
        try {
            Long orderId = readOrderId(event);
            OrderResponse response = orderService.getOrderById(orderId);
            return jsonResponse(200, response);
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
            log.error("Unexpected error while getting order in Lambda", ex);
            ApiErrorResponse body = new ApiErrorResponse(
                    "error",
                    "INTERNAL_SERVER_ERROR",
                    "Unexpected server error",
                    Map.of("exception", ex.getClass().getSimpleName()),
                    500
            );
            return jsonResponse(500, body);
        }
    }

    // Only reading the id is guarded: the IllegalArgumentExceptions raised here are this handler's own messages about
    // the request, so they are the caller's mistake (400). One thrown later, inside the order service, is a server-side
    // failure; it must not be shown to the caller as a bad request with the exception's text as the message.
    private Long readOrderId(Map<String, Object> event) {
        try {
            return extractOrderId(event);
        } catch (IllegalArgumentException ex) {
            throw new ValidationError("INVALID_ORDER_ID", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Long extractOrderId(Map<String, Object> event) {
        Object pathParametersObject = event.get("pathParameters");
        if (!(pathParametersObject instanceof Map<?, ?> pathParameters)) {
            throw new IllegalArgumentException("Missing path parameter: id");
        }

        Object idObject = pathParameters.get("id");
        if (idObject == null || idObject.toString().isBlank()) {
            throw new IllegalArgumentException("Missing path parameter: id");
        }

        try {
            return Long.parseLong(idObject.toString());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Path parameter id must be a number");
        }
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
            return "{\"type\":\"error\",\"code\":\"JSON_SERIALIZATION_FAILED\","
                    + "\"message\":\"Failed to serialize response\",\"httpStatus\":500}";
        }
    }
}
