package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.OrderNotFoundException;
import com.page24.backend.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the API Gateway handler of GET /orders/{id}.
 * The OrderService is mocked; the handler's own job is to read the path parameter
 * and turn each outcome into an HTTP-shaped response.
 */
@ExtendWith(MockitoExtension.class)
class GetOrderHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OrderService orderService;

    private GetOrderHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GetOrderHandler(orderService);
    }

    @Test
    @DisplayName("an existing order returns 200 with the order as JSON")
    void returnsOrder() {
        // Given
        OrderResponse order = new OrderResponse();
        order.setId(7L);
        order.setPatientMrn("234567");
        order.setStatus("COMPLETED");
        when(orderService.getOrderById(7L)).thenReturn(order);

        // When
        Map<String, Object> response = handler.handleRequest(eventWithId("7"));

        // Then
        assertThat(response.get("statusCode")).isEqualTo(200);
        assertThat(headers(response))
                .containsEntry("Content-Type", "application/json")
                .containsEntry("Access-Control-Allow-Origin", "*");
        JsonNode body = body(response);
        assertThat(body.get("id").asLong()).isEqualTo(7L);
        assertThat(body.get("patientMrn").asText()).isEqualTo("234567");
        assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("an order that has a date of birth is serialized as a normal 200 response")
    void returnsOrderThatHasDateOfBirth() {
        // Given: OrderResponse carries a java.time.LocalDate, which plain Jackson cannot
        // write unless the Java time module is registered
        OrderResponse order = new OrderResponse();
        order.setId(7L);
        order.setPatientMrn("234567");
        order.setPatientDateOfBirth(LocalDate.of(1985, 3, 22));
        when(orderService.getOrderById(7L)).thenReturn(order);

        // When
        Map<String, Object> response = handler.handleRequest(eventWithId("7"));

        // Then: real order data, not the serialization-failure fallback body
        assertThat(response.get("statusCode")).isEqualTo(200);
        assertThat(response.get("body").toString()).doesNotContain("JSON_SERIALIZATION_FAILED");
        assertThat(body(response).get("id").asLong()).isEqualTo(7L);
    }

    @Test
    @DisplayName("an event without pathParameters returns 400")
    void rejectsMissingPathParameters() {
        Map<String, Object> response = handler.handleRequest(Map.of());

        assertBadRequest(response, "Missing path parameter: id");
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("pathParameters without an id returns 400")
    void rejectsMissingId() {
        Map<String, Object> response = handler.handleRequest(Map.of("pathParameters", Map.of()));

        assertBadRequest(response, "Missing path parameter: id");
    }

    @Test
    @DisplayName("a null id returns 400")
    void rejectsNullId() {
        Map<String, Object> pathParameters = new HashMap<>();
        pathParameters.put("id", null);

        Map<String, Object> response = handler.handleRequest(Map.of("pathParameters", pathParameters));

        assertBadRequest(response, "Missing path parameter: id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank id returns 400")
    void rejectsBlankId(String id) {
        Map<String, Object> response = handler.handleRequest(eventWithId(id));

        assertBadRequest(response, "Missing path parameter: id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "12abc"})
    @DisplayName("a non-numeric id returns 400")
    void rejectsNonNumericId(String id) {
        Map<String, Object> response = handler.handleRequest(eventWithId(id));

        assertBadRequest(response, "Path parameter id must be a number");
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("an unknown order returns 404 with the ORDER_NOT_FOUND code")
    void returns404ForUnknownOrder() {
        when(orderService.getOrderById(99L)).thenThrow(new OrderNotFoundException(99L));

        Map<String, Object> response = handler.handleRequest(eventWithId("99"));

        assertThat(response.get("statusCode")).isEqualTo(404);
        assertThat(body(response).get("code").asText()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("an unexpected exception returns a generic 500 without leaking its message")
    void hidesUnexpectedErrorDetails() {
        when(orderService.getOrderById(7L)).thenThrow(new IllegalStateException("secret internal detail"));

        Map<String, Object> response = handler.handleRequest(eventWithId("7"));

        assertThat(response.get("statusCode")).isEqualTo(500);
        JsonNode body = body(response);
        assertThat(body.get("code").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(body.get("detail").get("exception").asText()).isEqualTo("IllegalStateException");
        assertThat(response.get("body").toString()).doesNotContain("secret internal detail");
    }

    private Map<String, Object> eventWithId(String id) {
        return Map.of("pathParameters", Map.of("id", id));
    }

    private void assertBadRequest(Map<String, Object> response, String expectedMessage) {
        assertThat(response.get("statusCode")).isEqualTo(400);
        JsonNode body = body(response);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_ORDER_ID");
        assertThat(body.get("message").asText()).isEqualTo(expectedMessage);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> headers(Map<String, Object> response) {
        return (Map<String, String>) response.get("headers");
    }

    private JsonNode body(Map<String, Object> response) {
        try {
            return objectMapper.readTree((String) response.get("body"));
        } catch (Exception e) {
            throw new AssertionError("Response body is not valid JSON: " + response.get("body"), e);
        }
    }
}
