package com.page24.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ClaudeService} against a fake provider ({@link FakeLlmApiServer}) on the loopback interface: what it sends,
 * what it returns and how every kind of failure surfaces. No real key and no real network are involved.
 */
class ClaudeServiceTest {

    private static final String API_KEY = "sk-test-not-a-real-key";
    private static final String PATH = "/v1/messages";
    private static final String PATIENT_INFO = "Patient: Müller 患者, MRN 123456, on infliximab";
    private static final String WRAPPED_MESSAGE = "Claude failed to generate care plan";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeLlmApiServer server;

    @BeforeEach
    void startFakeProvider() {
        server = FakeLlmApiServer.start();
    }

    @AfterEach
    void stopFakeProvider() {
        server.close();
    }

    private ClaudeService serviceWithKey(String apiKey) {
        return new ClaudeService(apiKey, server.url(PATH), "test-model");
    }

    private ClaudeService service() {
        return serviceWithKey(API_KEY);
    }

    @Test
    @DisplayName("Returns the text of the first content block of the reply")
    void returnsTheTextOfTheFirstContentBlock() {
        server.respondWith(200, """
                {"id":"msg_1","type":"message","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"Care plan: monitor CBC"},{"type":"text","text":"ignored"}]}""");

        String carePlan = service().generateCarePlan(PATIENT_INFO);

        assertThat(carePlan).isEqualTo("Care plan: monitor CBC");
    }

    @Test
    @DisplayName("Sends one POST with the key and version headers and a body made of the model, limit and prompt")
    void sendsTheExpectedRequest() throws IOException {
        server.respondWith(200, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
        ClaudeService service = service();

        service.generateCarePlan(PATIENT_INFO);

        FakeLlmApiServer.RecordedRequest request = server.onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(PATH);
        assertThat(request.header("x-api-key")).isEqualTo(API_KEY);
        assertThat(request.header("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(request.header("content-type")).startsWith("application/json");

        JsonNode body = MAPPER.readTree(request.body());
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(1000);
        assertThat(body.get("messages").size()).isEqualTo(1);
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asText())
                .as("the prompt carries the patient information, non-ASCII characters intact")
                .isEqualTo(service.buildPrompt(PATIENT_INFO))
                .contains(PATIENT_INFO);
    }

    @Test
    @DisplayName("The key travels only in the x-api-key header, never in the body")
    void keepsTheKeyOutOfTheBody() {
        server.respondWith(200, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");

        service().generateCarePlan(PATIENT_INFO);

        assertThat(server.onlyRequest().body()).doesNotContain(API_KEY);
    }

    @ParameterizedTest(name = "[{index}] key \"{0}\" is a missing key")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("A null, empty or blank key fails before any request, with a message that names the setting")
    void refusesToCallWithoutAKey(String apiKey) {
        ClaudeService service = serviceWithKey(apiKey);

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Claude API key is missing. Set llm.claude.api.key or switch llm.provider.");
        assertThat(server.requests()).as("nothing is sent without a key").isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{}",
            "null",
            "{\"content\":null}",
            "{\"content\":[]}",
            "{\"content\":\"just text\"}",
            "{\"content\":{\"text\":\"an object, not a list\"}}"
    })
    @DisplayName("A reply without a content list that has a first block is reported as an unexpected format")
    void reportsAnUnexpectedFormat(String reply) {
        server.respondWith(200, reply);
        ClaudeService service = service();

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasRootCauseMessage("Claude response format is unexpected: missing content[0].text");
    }

    @Test
    @DisplayName("A first content block that has no text field fails as a wrapped error")
    void failsWhenTheFirstBlockHasNoText() {
        server.respondWith(200, "{\"content\":[{\"type\":\"tool_use\"}]}");
        ClaudeService service = service();

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("A reply that is not JSON fails as a wrapped error caused by the JSON parser")
    void failsOnAReplyThatIsNotJson() {
        server.respondWith(200, "<html>bad gateway</html>");
        ClaudeService service = service();

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("A successful reply with no body at all fails as a wrapped error")
    void failsOnAnEmptyReply() {
        server.respondWith(200, "");
        ClaudeService service = service();

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {400, 401, 404, 429, 500, 503})
    @DisplayName("An HTTP error status is wrapped, keeps its status code as the cause and does not echo the key")
    void wrapsAnHttpErrorStatus(int status) {
        server.respondWith(status, "{\"type\":\"error\",\"error\":{\"message\":\"provider said no\"}}");
        ClaudeService service = service();

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasCauseInstanceOf(WebClientResponseException.class)
                .satisfies(ex -> {
                    WebClientResponseException cause = (WebClientResponseException) ex.getCause();
                    assertThat(cause.getStatusCode().value()).isEqualTo(status);
                    assertThat(ex.getMessage()).doesNotContain(API_KEY);
                    assertThat(cause.getMessage()).doesNotContain(API_KEY);
                });
        assertThat(server.requests()).as("one attempt per call: retrying is the caller's job").hasSize(1);
    }

    @Test
    @DisplayName("A provider that cannot be reached is wrapped, caused by the connection error")
    void wrapsAConnectionFailure() {
        String deadUrl;
        try (FakeLlmApiServer closed = FakeLlmApiServer.start()) {
            deadUrl = closed.url(PATH);
        }
        ClaudeService service = new ClaudeService(API_KEY, deadUrl, "test-model");

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasCauseInstanceOf(WebClientRequestException.class);
    }
}
