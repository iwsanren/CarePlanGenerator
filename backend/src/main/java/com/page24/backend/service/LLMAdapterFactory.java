package com.page24.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Day10 路由层：集中处理 provider 选择，避免业务层出现 vendor if/else。
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
		// 兼容历史语义：mockEnabled=true 时，优先走本地 mock。
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
