package com.page24.backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The LLM call through the real Spring Retry proxy, including the real 2 s and 4 s back-off. */
class CarePlanLlmCallerRetryTest {

    private AnnotationConfigApplicationContext context;
    private CarePlanLlmCaller caller;
    private BaseLLMService llm;

    @BeforeEach
    void startContext() {
        context = new AnnotationConfigApplicationContext(RetrySetup.class);
        caller = context.getBean(CarePlanLlmCaller.class);
        llm = mock(BaseLLMService.class);
        LLMAdapterFactory factory = context.getBean(LLMAdapterFactory.class);
        reset(factory);
        when(factory.getService()).thenReturn(llm);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    @DisplayName("two failures then success -> the content, after 3 attempts")
    void succeedsOnTheThirdAttempt() {
        when(llm.generateCarePlan("prompt"))
                .thenThrow(new IllegalStateException("1"))
                .thenThrow(new IllegalStateException("2"))
                .thenReturn("the plan");

        CarePlanLlmCaller.Outcome outcome = caller.generate(7L, "prompt");

        assertThat(outcome.content()).isEqualTo("the plan");
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(outcome.gaveUp()).isFalse();
    }

    @Test
    @DisplayName("three failures -> 'gave up' after 3 attempts, returned, not thrown")
    void givesUpAfterThreeAttempts() {
        when(llm.generateCarePlan("prompt")).thenThrow(new IllegalStateException("down"));

        CarePlanLlmCaller.Outcome outcome = caller.generate(7L, "prompt");

        assertThat(outcome.gaveUp()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(3);
        verify(llm, times(3)).generateCarePlan("prompt");
    }

    @Configuration
    @EnableRetry
    static class RetrySetup {

        @Bean
        LLMAdapterFactory llmAdapterFactory() {
            return mock(LLMAdapterFactory.class);
        }

        @Bean
        CarePlanLlmCaller carePlanLlmCaller(LLMAdapterFactory factory) {
            return new CarePlanLlmCaller(factory);
        }
    }
}
