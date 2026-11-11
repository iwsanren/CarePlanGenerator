package com.page24.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI provider implementation responsible for communicating with the OpenAI API.
 */
@Service("openai")
@Slf4j
public class OpenAIService implements BaseLLMService {

    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAIService(@Value("${llm.api.key}") String apiKey,
                         @Value("${llm.api.url}") String apiUrl,
                         @Value("${llm.openai.model:gpt-3.5-turbo}") String model,
                         @Value("${llm.request-timeout:60s}") Duration requestTimeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.webClient = WebClient.builder().baseUrl(apiUrl).build();
    }

    @Override
    public String generateCarePlan(String patientInfo) {
        // Checked per call, not in the constructor: the bean is also created when the local mock provider is in use,
        // and an empty key must not stop the application from starting then. Without this check an empty key would
        // still be sent as "Bearer " and answered with a 401 that the caller then retries.
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI API key is missing. Set llm.api.key or switch llm.provider.");
        }

        try {
            String prompt = buildPrompt(patientInfo);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a clinical pharmacist expert."),
                    Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("max_tokens", 1000);
            requestBody.put("temperature", 0.7);

            String response = webClient.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    // Bounds the whole call. Without it a provider that accepts the connection and never answers
                    // would block this thread for good, and the retry in the caller would never get its turn.
                    .timeout(requestTimeout)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            // path() never returns null, so a missing level ends as a missing node instead of a NullPointerException.
            // Only a non-blank string counts as a care plan: asText() would turn a JSON null into the text "null"
            // and keep an empty string, and either would be saved as a completed plan.
            JsonNode contentNode = jsonNode.path("choices").path(0).path("message").path("content");
            if (contentNode.isTextual() && !contentNode.asText().isBlank()) {
                return contentNode.asText();
            }
            throw new RuntimeException("OpenAI response format is unexpected: missing choices[0].message.content");
        } catch (Exception e) {
            log.error("OpenAI 调用失败: {}", e.getMessage());
            throw new RuntimeException("OpenAI failed to generate care plan", e);
        }
    }

    // package-private so RequiredCarePlanSectionsTest can call it directly without a live API key
    String buildPrompt(String patientInfo) {
        return String.format("""
                Based on the following patient information, generate a comprehensive care plan.

                Patient Information:
                %s

                Please provide a care plan that includes:
                1. Problem list / Drug therapy problems (DTPs)
                2. Goals (SMART)
                3. Pharmacist interventions / plan
                4. Monitoring plan & lab schedule
                """, patientInfo);
    }
}
