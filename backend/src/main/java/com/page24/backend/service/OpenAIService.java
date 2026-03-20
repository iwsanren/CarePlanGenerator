package com.page24.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI provider 实现：负责和 OpenAI API 通信。
 */
@Service("openai")
@Slf4j
public class OpenAIService implements BaseLLMService {

    private final String apiKey;
    private final String model;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAIService(@Value("${llm.api.key}") String apiKey,
                         @Value("${llm.api.url}") String apiUrl,
                         @Value("${llm.openai.model:gpt-3.5-turbo}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.webClient = WebClient.builder().baseUrl(apiUrl).build();
    }

    @Override
    public String generateCarePlan(String patientInfo) {
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
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            return jsonNode.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            log.error("OpenAI 调用失败: {}", e.getMessage());
            throw new RuntimeException("OpenAI failed to generate care plan", e);
        }
    }

    private String buildPrompt(String patientInfo) {
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
