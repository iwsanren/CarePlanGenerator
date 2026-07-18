package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.ApiErrorResponse;
import com.page24.backend.exception.BaseAppException;
import com.page24.backend.service.OrderService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API Gateway Lambda handler for:
 *   GET /orders/{id}
 *
 * Handler setting in AWS Lambda:
 *   com.page24.backend.aws.lambda.GetOrderHandler::handleRequest
 */
public class GetOrderHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderService orderService;

    public GetOrderHandler() {
        this.orderService = LambdaSpringContext.getContext().getBean(OrderService.class);
    }

    public Map<String, Object> handleRequest(Map<String, Object> event) {
        try {
            Long orderId = extractOrderId(event);
            OrderResponse response = orderService.getOrderById(orderId);
            return jsonResponse(200, response);
        } catch (IllegalArgumentException ex) {
            ApiErrorResponse body = new ApiErrorResponse(
                    "validation",
                    "INVALID_ORDER_ID",
                    ex.getMessage(),
                    null,
                    400
            );
            return jsonResponse(400, body);
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
            return "{\"type\":\"error\",\"code\":\"JSON_SERIALIZATION_FAILED\",\"message\":\"Failed to serialize response\",\"httpStatus\":500}";
        }
    }
}
