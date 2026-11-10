package com.page24.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OpenAIService} against a fake provider ({@link FakeLlmApiServer}) on the loopback interface: what it sends,
 * what it returns and how every kind of failure surfaces. No real key and no real network are involved.
 */
class OpenAIServiceTest {

    private static final String API_KEY = "sk-test-not-a-real-key";
    private static final String PATH = "/v1/chat/completions";
    private static final String PATIENT_INFO = "Patient: Müller 患者, MRN 123456, on infliximab";
    private static final String WRAPPED_MESSAGE = "OpenAI failed to generate care plan";
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

    private OpenAIService service() {
        return new OpenAIService(API_KEY, server.url(PATH), "test-model", Duration.ofSeconds(30));
    }

    private static String reply(String content) {
        return "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + content
                + "\"},\"finish_reason\":\"stop\"}]}";
    }

    @Test
    @DisplayName("Returns the content of the message of the first choice")
    void returnsTheContentOfTheFirstChoice() {
        server.respondWith(200, """
                {"id":"chatcmpl-1","object":"chat.completion",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Care plan: monitor CBC"}},
                            {"index":1,"message":{"role":"assistant","content":"ignored"}}]}""");

        String carePlan = service().generateCarePlan(PATIENT_INFO);

        assertThat(carePlan).isEqualTo("Care plan: monitor CBC");
    }

    @Test
    @DisplayName("Sends one POST with a bearer token and a body of model, system prompt, user prompt and limits")
    void sendsTheExpectedRequest() throws IOException {
        server.respondWith(200, reply("ok"));
        OpenAIService service = service();

        service.generateCarePlan(PATIENT_INFO);

        FakeLlmApiServer.RecordedRequest request = server.onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(PATH);
        assertThat(request.header("authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(request.header("content-type")).startsWith("application/json");

        JsonNode body = MAPPER.readTree(request.body());
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(1000);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        JsonNode messages = body.get("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("You are a clinical pharmacist expert.");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText())
                .as("the prompt carries the patient information, non-ASCII characters intact")
                .isEqualTo(service.buildPrompt(PATIENT_INFO))
                .contains(PATIENT_INFO);
    }

    @Test
    @DisplayName("The key travels only in the Authorization header, never in the body")
    void keepsTheKeyOutOfTheBody() {
        server.respondWith(200, reply("ok"));

        service().generateCarePlan(PATIENT_INFO);

        assertThat(server.onlyRequest().body()).doesNotContain(API_KEY);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{}",
            "null",
            "{\"choices\":null}",
            "{\"choices\":[]}",
            "{\"choices\":[{}]}",
            "{\"choices\":[{\"message\":{}}]}",
            "{\"choices\":[{\"message\":null}]}",
            "{\"choices\":[null]}",
            "{\"choices\":\"just text\"}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"\"}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}",
            "{\"choices\":[{\"message\":{\"content\":42}}]}",
            "{\"choices\":[{\"message\":{\"content\":{\"a\":1}}}]}"
    })
    @DisplayName("A reply without usable choices[0].message.content is not accepted as a care plan")
    void failsOnAnUnexpectedFormat(String reply) {
        server.respondWith(200, reply);
        OpenAIService service = service();

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasRootCauseMessage("OpenAI response format is unexpected: missing choices[0].message.content");
    }

    @Test
    @DisplayName("A reply that is not JSON fails as a wrapped error caused by the JSON parser")
    void failsOnAReplyThatIsNotJson() {
        server.respondWith(200, "<html>bad gateway</html>");
        OpenAIService service = service();

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("A successful reply with no body at all fails as a wrapped error")
    void failsOnAnEmptyReply() {
        server.respondWith(200, "");
        OpenAIService service = service();

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {400, 401, 404, 429, 500, 503})
    @DisplayName("An HTTP error status is wrapped, keeps its status code as the cause and does not echo the key")
    void wrapsAnHttpErrorStatus(int status) {
        server.respondWith(status, "{\"error\":{\"message\":\"provider said no\"}}");
        OpenAIService service = service();

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
        OpenAIService service = new OpenAIService(API_KEY, deadUrl, "test-model", Duration.ofSeconds(30));

        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasCauseInstanceOf(WebClientRequestException.class);
    }

    @Test
    @DisplayName("A provider that answers too slowly is given up on at the timeout, as a wrapped timeout error")
    void givesUpOnAProviderThatAnswersTooSlowly() {
        // The reply would arrive after 10 seconds; the service is only willing to wait 300 milliseconds.
        server.respondAfter(10_000, 200, reply("too late"));
        OpenAIService service = new OpenAIService(API_KEY, server.url(PATH), "test-model", Duration.ofMillis(300));

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.generateCarePlan(PATIENT_INFO))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(WRAPPED_MESSAGE)
                .hasRootCauseInstanceOf(TimeoutException.class);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(waited).as("returned at the timeout, not when the provider finally answered")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("A reply that arrives within the timeout is returned as usual")
    void returnsAReplyThatArrivesWithinTheTimeout() {
        server.respondAfter(100, 200, reply("in time"));
        OpenAIService service = new OpenAIService(API_KEY, server.url(PATH), "test-model", Duration.ofSeconds(10));

        assertThat(service.generateCarePlan(PATIENT_INFO)).isEqualTo("in time");
    }
}
