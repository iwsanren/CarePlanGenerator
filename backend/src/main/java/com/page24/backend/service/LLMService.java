package com.page24.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
/**
 * Backward-compatible facade that can be removed after callers migrate.
 * Existing code may continue to call LLMService.generateCarePlan(...); requests are delegated to the Day 10 factory and provider.
 */
@Service
@Slf4j
public class LLMService {

    private final LLMAdapterFactory llmAdapterFactory;

    public LLMService(LLMAdapterFactory llmAdapterFactory) {
        this.llmAdapterFactory = llmAdapterFactory;
    }

    public String generateCarePlan(String patientInfo) {
        log.debug("LLMService facade forwarding to provider from LLMAdapterFactory");
        return llmAdapterFactory.getService().generateCarePlan(patientInfo);
    }
}

