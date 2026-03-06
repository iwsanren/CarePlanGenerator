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

@Service
@Slf4j
public class LLMService {

    private final WebClient webClient;
    private final String apiKey;
    private final boolean mockEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LLMService(@Value("${llm.api.key}") String apiKey,
                      @Value("${llm.api.url}") String apiUrl,
                      @Value("${llm.mock.enabled:false}") boolean mockEnabled) {
        this.apiKey = apiKey;
        this.mockEnabled = mockEnabled;
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .build();
    }

    public String generateCarePlan(String patientInfo) {
        // 如果开启了 mock 模式，直接返回固定文字，不调真实 LLM
        // 好处：不花钱、不等待、把"我的代码 bug"和"LLM 出了问题"分开
        if (mockEnabled) {
            return generateMockCarePlan(patientInfo);
        }
        return callRealLLM(patientInfo);
    }

    /**
     * Mock 假函数：直接返回一段固定的 Care Plan 文字
     * 用途：开发、测试时使用，完全不调 LLM API
     */
    private String generateMockCarePlan(String patientInfo) {
        log.info("🧪 [MOCK] 使用假 LLM，不调真实 API. 收到病人信息长度: {} 字符", patientInfo.length());
        return """
                [MOCK CARE PLAN - 测试用，非真实 LLM 生成]

                **Problem list / Drug therapy problems (DTPs)**
                - Need for rapid immunomodulation to reduce myasthenic symptoms
                - Risk of infusion-related reactions
                - Risk of renal dysfunction or volume overload
                - Risk of thromboembolic events
                - Potential drug-drug interactions
                - Patient education / adherence gap

                **Goals (SMART)**
                - Primary: Achieve clinically meaningful improvement in muscle strength within 2 weeks
                - Safety goal: No severe infusion reaction, no acute kidney injury
                - Process: Complete full 2 g/kg course with documented monitoring

                **Pharmacist interventions / plan**
                - Dosing & Administration: Verify dose based on actual body weight
                - Premedication: Acetaminophen 650 mg PO + diphenhydramine 25 mg IV 30 min before
                - Infusion rates & titration: Start at 0.5 mL/kg/hr, increase every 30 min as tolerated
                - Hydration & renal protection: Adequate hydration before and during infusion
                - Monitoring during infusion: Vitals q15 min for first hour, then q30 min
                - Adverse event management: Hold infusion for any Grade 2+ reactions

                **Monitoring plan & lab schedule**
                - Before first infusion: CBC, BMP, baseline vitals, urinalysis
                - During each infusion: Vitals q15-30 min, urine output monitoring
                - Post-course (3-7 days): BMP to check renal function, repeat CBC
                - Long-term: Monthly neurological assessment, MGFA classification tracking
                """;
    }

    /**
     * 真实 LLM 调用：生产环境使用
     */
    private String callRealLLM(String patientInfo) {
        try {
            String prompt = buildPrompt(patientInfo);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-3.5-turbo");
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
            throw new RuntimeException("Failed to generate care plan: " + e.getMessage(), e);
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

