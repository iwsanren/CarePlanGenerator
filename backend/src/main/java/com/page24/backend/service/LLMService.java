package com.page24.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
/**
 * 兼容层（可后续删除）：
 * 旧代码仍可调用 LLMService.generateCarePlan(...), 内部转发到 Day10 的 factory + provider。
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

