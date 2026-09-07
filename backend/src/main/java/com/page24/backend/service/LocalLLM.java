package com.page24.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Local mock provider for development and testing; it never calls an external API.
 */
@Service("local")
@Slf4j
public class LocalLLM implements BaseLLMService {

    @Override
    public String generateCarePlan(String patientInfo) {
        log.info("[LOCAL LLM] Generate a care plan using mock content, patientInfoLength={}", patientInfo.length());
        return """
                [LOCAL MOCK CARE PLAN]

                Problem list
                - Need for therapy optimization
                - Potential adherence risk

                Goals
                - Improve symptom control in 2 weeks
                - Minimize adverse events during treatment

                Pharmacist interventions
                - Verify dose and administration schedule
                - Provide adverse event counseling
                - Reconcile medication history

                Monitoring plan
                - Baseline CBC/BMP and vitals
                - Infusion-day vitals every 15-30 minutes
                - Follow-up renal function in 3-7 days
                """;
    }
}
