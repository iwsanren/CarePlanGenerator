package com.page24.backend.service;

/**
 * Day10 Adapter 抽象层：业务只依赖这个接口，不依赖具体 vendor。
 * 这样以后切 OpenAI/Claude/Local 时，不需要改业务调用代码。
 */
public interface BaseLLMService {

    String generateCarePlan(String patientInfo);

}
