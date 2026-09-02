package com.page24.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Day 10 routing layer: centralizes provider selection so business code avoids vendor-specific conditionals.
 */
@Component
@Slf4j
public class LLMAdapterFactory {

	private final Map<String, BaseLLMService> llmServices;
	private final String provider;
	private final boolean mockEnabled;

	public LLMAdapterFactory(Map<String, BaseLLMService> llmServices,
							 @Value("${llm.provider:openai}") String provider,
							 @Value("${llm.mock.enabled:false}") boolean mockEnabled) {
		this.llmServices = llmServices;
		this.provider = normalize(provider);
		this.mockEnabled = mockEnabled;
	}

	public BaseLLMService getService() {
		// Preserve legacy behavior: when mockEnabled is true, prefer the local mock provider.
		if (mockEnabled) {
			return getRequired("local");
		}
		return getRequired(provider);
	}

	private BaseLLMService getRequired(String serviceKey) {
		BaseLLMService service = llmServices.get(serviceKey);
		if (service == null) {
			throw new IllegalArgumentException("Unsupported llm.provider='" + serviceKey + "'. Available: " + llmServices.keySet());
		}
		log.info("LLM provider selected: {} (mockEnabled={})", serviceKey, mockEnabled);
		return service;
	}

	private String normalize(String rawProvider) {
		if (rawProvider == null || rawProvider.isBlank()) {
			return "openai";
		}
		return rawProvider.trim().toLowerCase();
	}

}
