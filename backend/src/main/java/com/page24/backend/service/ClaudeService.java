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
 * Claude provider implementation with the structure needed for a live API integration.
 * Throws a clear error when the API key is missing, making configuration issues easy to diagnose.
 */
@Service("claude")
@Slf4j
public class ClaudeService implements BaseLLMService {

    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaudeService(@Value("${llm.claude.api.key:}") String apiKey,
                         @Value("${llm.claude.api.url:https://api.anthropic.com/v1/messages}") String apiUrl,
                         @Value("${llm.claude.model:claude-3-5-sonnet-latest}") String model,
                         @Value("${llm.request-timeout:60s}") Duration requestTimeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.webClient = WebClient.builder().baseUrl(apiUrl).build();
    }

    @Override
    public String generateCarePlan(String patientInfo) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Claude API key is missing. Set llm.claude.api.key or switch llm.provider.");
        }

        try {
            String prompt = buildPrompt(patientInfo);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 1000);
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", prompt)
            ));

            String response = webClient.post()
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    // Bounds the whole call. Without it a provider that accepts the connection and never answers
                    // would block this thread for good, and the retry in the caller would never get its turn.
                    .timeout(requestTimeout)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode contentNode = jsonNode.get("content");
            if (contentNode != null && contentNode.isArray() && !contentNode.isEmpty()) {
                // Only a non-blank string counts as a care plan. asText() would turn a JSON null into the
                // text "null" and keep an empty string, and either would be saved as a completed plan.
                JsonNode textNode = contentNode.get(0).get("text");
                if (textNode != null && textNode.isTextual() && !textNode.asText().isBlank()) {
                    return textNode.asText();
                }
            }
            throw new RuntimeException("Claude response format is unexpected: missing content[0].text");
        } catch (Exception e) {
            log.error("Claude 调用失败: {}", e.getMessage());
            throw new RuntimeException("Claude failed to generate care plan", e);
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
