package com.page24.backend.service;

/**
 * Day 10 adapter abstraction: business code depends on this interface, not a specific provider.
 * Switching among OpenAI, Claude, and local implementations does not change business call sites.
 */
public interface BaseLLMService {

    String generateCarePlan(String patientInfo);

}
