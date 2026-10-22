package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.dto.CreateOrderRequest;
import com.page24.backend.dto.OrderResponse;
import com.page24.backend.exception.OrderNotFoundException;
import com.page24.backend.service.OrderService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the API Gateway handler of POST /orders.
 *
 * The OrderService is mocked, so these tests only cover what the handler itself is
 * responsible for: parsing the API Gateway event, validating the body and turning
 * every outcome into an HTTP-shaped response map. The validator is the real
 * Hibernate Validator so that the Bean Validation annotations on
 * CreateOrderRequest are exercised for real.
 */
@ExtendWith(MockitoExtension.class)
class CreateOrderHandlerTest {

    private static final String VALID_BODY = """
            {
              "patientFirstName": "Jane",
              "patientLastName": "Smith",
              "patientMrn": "234567",
              "patientDateOfBirth": "1985-03-22",
              "providerName": "Dr. Emily Johnson",
              "providerNpi": "0987654321",
              "medicationName": "Gamunex-C",
              "primaryDiagnosis": "G70.00",
              "additionalDiagnoses": ["E11.9", "I10"],
              "medicationHistory": ["Metformin 500mg twice daily"],
              "patientRecords": "Progressive weakness over 3 weeks.",
              "confirm": true
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private OrderService orderService;

    private CreateOrderHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CreateOrderHandler(objectMapper, orderService, validator);
    }

    @Test
    @DisplayName("a valid body creates the order and returns 201 with JSON and CORS headers")
    void createsOrderAndReturns201() throws Exception {
        // Given: the service accepts the request and returns a saved order
        OrderResponse saved = new OrderResponse();
        saved.setId(7L);
        saved.setPatientMrn("234567");
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(saved);

        // When
        Map<String, Object> response = handler.handleRequest(Map.of("body", VALID_BODY));

        // Then: 201 + JSON body + the headers a browser client needs
        assertThat(response.get("statusCode")).isEqualTo(201);
        assertThat(headers(response))
                .containsEntry("Content-Type", "application/json")
                .containsEntry("Access-Control-Allow-Origin", "*");
        JsonNode body = body(response);
        assertThat(body.get("id").asLong()).isEqualTo(7L);
        assertThat(body.get("patientMrn").asText()).isEqualTo("234567");
    }

    @Test
    @DisplayName("every field of the JSON body is mapped onto the CreateOrderRequest passed to the service")
    void mapsAllFieldsOntoRequest() {
        // Given
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(new OrderResponse());

        // When
        handler.handleRequest(Map.of("body", VALID_BODY));

        // Then: capture what the service actually received
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService).createOrder(captor.capture());
        CreateOrderRequest request = captor.getValue();
        assertThat(request.getPatientFirstName()).isEqualTo("Jane");
        assertThat(request.getPatientLastName()).isEqualTo("Smith");
        assertThat(request.getPatientMrn()).isEqualTo("234567");
        assertThat(request.getPatientDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 22));
        assertThat(request.getProviderName()).isEqualTo("Dr. Emily Johnson");
        assertThat(request.getProviderNpi()).isEqualTo("0987654321");
        assertThat(request.getMedicationName()).isEqualTo("Gamunex-C");
        assertThat(request.getPrimaryDiagnosis()).isEqualTo("G70.00");
        assertThat(request.getAdditionalDiagnoses()).containsExactly("E11.9", "I10");
        assertThat(request.getMedicationHistory()).containsExactly("Metformin 500mg twice daily");
        assertThat(request.getPatientRecords()).isEqualTo("Progressive weakness over 3 weeks.");
        assertThat(request.getConfirm()).isTrue();
    }

    @Test
    @DisplayName("a Base64-encoded body (isBase64Encoded=true) is decoded before parsing")
    void decodesBase64Body() {
        // Given: API Gateway sometimes delivers the body Base64-encoded
        String encoded = Base64.getEncoder().encodeToString(VALID_BODY.getBytes(StandardCharsets.UTF_8));
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(new OrderResponse());

        // When
        Map<String, Object> response = handler.handleRequest(Map.of("body", encoded, "isBase64Encoded", true));

        // Then
        assertThat(response.get("statusCode")).isEqualTo(201);
    }

    @Test
    @DisplayName("optional fields may be omitted or null")
    void acceptsMissingOptionalFields() {
        // Given: only the required fields; lists, date and confirm are absent or null
        String body = """
                {
                  "patientFirstName": "Jane",
                  "patientLastName": "Smith",
                  "patientMrn": "234567",
                  "patientDateOfBirth": null,
                  "providerName": "Dr. Emily Johnson",
                  "providerNpi": "0987654321",
                  "medicationName": "Gamunex-C",
                  "primaryDiagnosis": "G70.00",
                  "additionalDiagnoses": null
                }
                """;
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(new OrderResponse());

        // When
        Map<String, Object> response = handler.handleRequest(Map.of("body", body));

        // Then
        assertThat(response.get("statusCode")).isEqualTo(201);
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService).createOrder(captor.capture());
        assertThat(captor.getValue().getPatientDateOfBirth()).isNull();
        assertThat(captor.getValue().getAdditionalDiagnoses()).isNull();
        assertThat(captor.getValue().getConfirm()).isNull();
    }

    @Test
    @DisplayName("an event without a body returns 400 and never calls the service")
    void rejectsMissingBody() {
        Map<String, Object> response = handler.handleRequest(Map.of());

        assertBadRequest(response, "Request body is required");
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("a blank body returns 400")
    void rejectsBlankBody() {
        Map<String, Object> response = handler.handleRequest(Map.of("body", "   "));

        assertBadRequest(response, "Request body is required");
    }

    @Test
    @DisplayName("malformed JSON returns 400")
    void rejectsMalformedJson() {
        Map<String, Object> response = handler.handleRequest(Map.of("body", "{not json"));

        assertBadRequest(response, "Malformed or unreadable JSON request body");
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("a JSON body that is not an object returns 400")
    void rejectsNonObjectBody() {
        Map<String, Object> response = handler.handleRequest(Map.of("body", "[1, 2, 3]"));

        assertBadRequest(response, "Request body must be a JSON object");
    }

    @Test
    @DisplayName("a string field given as a number returns 400 naming the field")
    void rejectsNonStringTextField() {
        Map<String, Object> response = handler.handleRequest(Map.of("body", "{\"patientMrn\": 234567}"));

        assertBadRequest(response, "patientMrn must be a string");
    }

    @Test
    @DisplayName("a date that is not yyyy-MM-dd returns 400")
    void rejectsBadDateFormat() {
        Map<String, Object> response =
                handler.handleRequest(Map.of("body", "{\"patientDateOfBirth\": \"22/03/1985\"}"));

        assertBadRequest(response, "patientDateOfBirth must use yyyy-MM-dd format");
    }

    @Test
    @DisplayName("a blank date is treated as absent, not as an error")
    void treatsBlankDateAsAbsent() {
        // Given: a blank date string plus otherwise-invalid body, so we reach bean validation
        Map<String, Object> response = handler.handleRequest(Map.of("body", "{\"patientDateOfBirth\": \"  \"}"));

        // Then: the failure comes from required-field validation, not from the date
        assertThat(response.get("statusCode")).isEqualTo(400);
        assertThat(body(response).get("detail").has("patientDateOfBirth")).isFalse();
    }

    @Test
    @DisplayName("a list field that is not an array returns 400")
    void rejectsNonArrayList() {
        Map<String, Object> response =
                handler.handleRequest(Map.of("body", "{\"additionalDiagnoses\": \"E11.9\"}"));

        assertBadRequest(response, "additionalDiagnoses must be an array of strings");
    }

    @Test
    @DisplayName("a list containing a non-string returns 400")
    void rejectsNonStringListItem() {
        Map<String, Object> response =
                handler.handleRequest(Map.of("body", "{\"medicationHistory\": [\"ok\", 5]}"));

        assertBadRequest(response, "medicationHistory must contain only strings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"yes\"", "1"})
    @DisplayName("a confirm flag that is not a boolean returns 400")
    void rejectsNonBooleanConfirm(String value) {
        Map<String, Object> response = handler.handleRequest(Map.of("body", "{\"confirm\": " + value + "}"));

        assertBadRequest(response, "confirm must be a boolean");
    }

    @Test
    @DisplayName("bean validation failures return 400 with one entry per invalid field")
    void reportsValidationViolationsPerField() {
        // Given: MRN is 2 digits (must be 6) and the other required fields are missing
        Map<String, Object> response = handler.handleRequest(Map.of("body", "{\"patientMrn\": \"12\"}"));

        // Then
        assertThat(response.get("statusCode")).isEqualTo(400);
        JsonNode body = body(response);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST_BODY");
        assertThat(body.get("message").asText()).isEqualTo("Request body validation failed");
        assertThat(body.get("detail").get("patientMrn").asText()).isEqualTo("MRN must be exactly 6 digits");
        assertThat(body.get("detail").get("patientFirstName").asText()).isEqualTo("patientFirstName is required");
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("an application exception from the service keeps its own status, code and message")
    void passesThroughAppExceptions() {
        // Given: the service throws a domain exception that carries a 404
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenThrow(new OrderNotFoundException(99L));

        // When
        Map<String, Object> response = handler.handleRequest(Map.of("body", VALID_BODY));

        // Then
        assertThat(response.get("statusCode")).isEqualTo(404);
        assertThat(body(response).get("code").asText()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(body(response).get("httpStatus").asInt()).isEqualTo(404);
    }

    @Test
    @DisplayName("an unexpected exception returns a generic 500 without leaking its message")
    void hidesUnexpectedErrorDetails() {
        // Given: something unexpected blows up, with a message we must not expose
        when(orderService.createOrder(any(CreateOrderRequest.class)))
                .thenThrow(new IllegalStateException("secret database password in stack trace"));

        // When
        Map<String, Object> response = handler.handleRequest(Map.of("body", VALID_BODY));

        // Then: 500, a generic message, and only the exception class name as detail
        assertThat(response.get("statusCode")).isEqualTo(500);
        JsonNode body = body(response);
        assertThat(body.get("code").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(body.get("message").asText()).isEqualTo("Unexpected server error");
        assertThat(body.get("detail").get("exception").asText()).isEqualTo("IllegalStateException");
        assertThat(response.get("body").toString()).doesNotContain("secret database password");
    }

    private void assertBadRequest(Map<String, Object> response, String expectedMessage) {
        assertThat(response.get("statusCode")).isEqualTo(400);
        JsonNode body = body(response);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST_BODY");
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
