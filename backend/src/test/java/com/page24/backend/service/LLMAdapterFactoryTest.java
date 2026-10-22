package com.page24.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the LLM provider routing. Spring would normally inject the map of
 * provider beans keyed by bean name; here we build that map by hand with mocks.
 */
@ExtendWith(MockitoExtension.class)
class LLMAdapterFactoryTest {

    @Mock
    private BaseLLMService openai;

    @Mock
    private BaseLLMService claude;

    @Mock
    private BaseLLMService local;

    private Map<String, BaseLLMService> services() {
        return Map.of("openai", openai, "claude", claude, "local", local);
    }

    @Test
    @DisplayName("returns the configured provider when mock mode is off")
    void returnsConfiguredProvider() {
        LLMAdapterFactory factory = new LLMAdapterFactory(services(), "claude", false);

        assertThat(factory.getService()).isSameAs(claude);
    }

    @Test
    @DisplayName("the provider name is trimmed and lower-cased before lookup")
    void normalizesProviderName() {
        LLMAdapterFactory factory = new LLMAdapterFactory(services(), "  Claude ", false);

        assertThat(factory.getService()).isSameAs(claude);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("a missing or blank provider falls back to openai")
    void defaultsToOpenAi(String provider) {
        LLMAdapterFactory factory = new LLMAdapterFactory(services(), provider, false);

        assertThat(factory.getService()).isSameAs(openai);
    }

    @Test
    @DisplayName("mock mode always wins over the configured provider")
    void mockModeSelectsLocalProvider() {
        LLMAdapterFactory factory = new LLMAdapterFactory(services(), "claude", true);

        assertThat(factory.getService()).isSameAs(local);
    }

    @Test
    @DisplayName("an unknown provider fails with a message listing the available ones")
    void rejectsUnknownProvider() {
        LLMAdapterFactory factory = new LLMAdapterFactory(services(), "gemini", false);

        assertThatThrownBy(factory::getService)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported llm.provider='gemini'")
                .hasMessageContaining("openai");
    }

    @Test
    @DisplayName("mock mode without a local provider registered fails clearly")
    void rejectsMockModeWithoutLocalProvider() {
        LLMAdapterFactory factory = new LLMAdapterFactory(Map.of("openai", openai), "openai", true);

        assertThatThrownBy(factory::getService)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported llm.provider='local'");
    }
}
