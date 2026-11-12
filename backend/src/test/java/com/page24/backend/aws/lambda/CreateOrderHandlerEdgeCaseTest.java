package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.WarningException;
import com.page24.backend.service.OrderService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The cases of {@link CreateOrderHandler} that {@link CreateOrderHandlerTest} does not reach: an explicit
 * {@code null} or {@code false} confirm flag, a bad Base64 body, bodies that are valid JSON but not an object,
 * application exceptions other than not-found, and a response that cannot be turned into JSON.
 */
@ExtendWith(MockitoExtension.class)
class CreateOrderHandlerEdgeCaseTest {

    private static final String BODY_PREFIX = """
            {
              "patientFirstName": "Jane",
              "patientLastName": "Smith",
              "patientMrn": "234567",
              "providerName": "Dr. Emily Johnson",
              "providerNpi": "0987654321",
              "medicationName": "Gamunex-C",
              "primaryDiagnosis": "G70.00",
            """;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private OrderService orderService;

    private CreateOrderHandler handler() {
        return new CreateOrderHandler(objectMapper, orderService, validator);
    }

    private static String bodyWithConfirm(String confirmJson) {
        return BODY_PREFIX + "  \"confirm\": " + confirmJson + "\n}";
    }

    @Test
    @DisplayName("confirm: false reaches the service as false, not as absent")
    void passesAnExplicitFalseConfirm() {
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(new OrderResponse());

        Map<String, Object> response = handler().handleRequest(Map.of("body", bodyWithConfirm("false")));

        assertThat(response.get("statusCode")).isEqualTo(201);
        assertThat(capturedRequest().getConfirm()).isFalse();
    }

    @Test
    @DisplayName("confirm: null reaches the service as absent")
    void treatsAnExplicitNullConfirmAsAbsent() {
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(new OrderResponse());

        Map<String, Object> response = handler().handleRequest(Map.of("body", bodyWithConfirm("null")));

        assertThat(response.get("statusCode")).isEqualTo(201);
        assertThat(capturedRequest().getConfirm()).isNull();
    }

    @Test
    @DisplayName("A body marked Base64 that is not valid Base64 returns 400 and never calls the service")
    void rejectsAnInvalidBase64Body() {
        Map<String, Object> response =
                handler().handleRequest(Map.of("body", "!!! not base64 !!!", "isBase64Encoded", true));

        assertThat(response.get("statusCode")).isEqualTo(400);
        assertThat(body(response).get("code").asText()).isEqualTo("INVALID_REQUEST_BODY");
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest(name = "[{index}] body {0}")
    @ValueSource(strings = {"null", "42", "\"text\"", "true"})
    @DisplayName("A body that is valid JSON but not an object returns 400")
    void rejectsJsonThatIsNotAnObject(String json) {
        Map<String, Object> response = handler().handleRequest(Map.of("body", json));

        assertThat(response.get("statusCode")).isEqualTo(400);
        assertThat(body(response).get("message").asText()).isEqualTo("Request body must be a JSON object");
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("An IllegalArgumentException from the order service is a 500 that does not repeat its message")
    void doesNotTurnAFailureInsideTheOrderServiceIntoABadRequest() {
        when(orderService.createOrder(any(CreateOrderRequest.class)))
                .thenThrow(new IllegalArgumentException("internal detail: MRN 234567 for Jane Smith"));

        Map<String, Object> response = handler().handleRequest(Map.of("body", bodyWithConfirm("false")));

        assertThat(response.get("statusCode")).as("a server-side failure is not the caller's mistake").isEqualTo(500);
        JsonNode body = body(response);
        assertThat(body.get("code").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(body.get("message").asText()).isEqualTo("Unexpected server error");
        assertThat((String) response.get("body")).doesNotContain("internal detail").doesNotContain("Jane Smith");
    }

    @Test
    @DisplayName("An application exception keeps its type, code, message, detail and status")
    void keepsEveryFieldOfAnApplicationException() {
        WarningException warning = new WarningException("DUPLICATE_ORDER", "Possible duplicate", Map.of("orderId", 5));
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenThrow(warning);

        Map<String, Object> response = handler().handleRequest(Map.of("body", bodyWithConfirm("false")));

        assertThat(response.get("statusCode")).isEqualTo(warning.getHttpStatus().value());
        JsonNode body = body(response);
        assertThat(body.get("type").asText()).isEqualTo(warning.getType());
        assertThat(body.get("code").asText()).isEqualTo("DUPLICATE_ORDER");
        assertThat(body.get("message").asText()).isEqualTo("Possible duplicate");
        assertThat(body.get("detail").get("orderId").asInt()).isEqualTo(5);
        assertThat(body.get("httpStatus").asInt()).isEqualTo(warning.getHttpStatus().value());
    }

    @Test
    @DisplayName("A response that cannot be serialised falls back to a fixed JSON error body")
    void fallsBackWhenTheResponseCannotBeSerialised() {
        ObjectMapper failingWriter = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("cannot serialise in this test") { };
            }
        };
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(new OrderResponse());
        CreateOrderHandler handler = new CreateOrderHandler(failingWriter, orderService, validator);

        Map<String, Object> response = handler.handleRequest(Map.of("body", bodyWithConfirm("false")));

        assertThat(response.get("statusCode"))
                .as("the status code says 500 too, not the 201 of an order that could not be reported")
                .isEqualTo(500);
        JsonNode body = body(response);
        assertThat(body.get("type").asText()).isEqualTo("error");
        assertThat(body.get("code").asText()).isEqualTo("JSON_SERIALIZATION_FAILED");
        assertThat(body.get("message").asText()).isEqualTo("Failed to serialize response");
        assertThat(body.get("httpStatus").asInt()).isEqualTo(500);
    }

    private CreateOrderRequest capturedRequest() {
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService).createOrder(captor.capture());
        return captor.getValue();
    }

    private JsonNode body(Map<String, Object> response) {
        try {
            return objectMapper.readTree((String) response.get("body"));
        } catch (Exception e) {
            throw new AssertionError("Response body is not valid JSON: " + response.get("body"), e);
        }
    }
}
