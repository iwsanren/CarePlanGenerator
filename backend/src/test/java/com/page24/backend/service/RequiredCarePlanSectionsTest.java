package com.page24.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the "four required sections" contract (Gap_Roadmap §3.2):
 * every provider's output (or, for the two live providers, the prompt asking the
 * LLM to produce it) must reference problem list, goals, pharmacist interventions,
 * and monitoring plan. Claude/OpenAI can't be exercised end-to-end without a live
 * API key, so they're checked at the prompt-construction level instead.
 */
class RequiredCarePlanSectionsTest {

    // Loose keywords, not full headers, because each provider phrases them
    // slightly differently (LocalLLM's mock output vs. the instruction text
    // sent to Claude/OpenAI).
    private static final String[] REQUIRED_SECTION_KEYWORDS = {
            "Problem list",
            "Goals",
            "Pharmacist interventions",
            "Monitoring plan",
    };

    @Test
    @DisplayName("LocalLLM mock output contains all four required sections")
    void localLlmOutputHasAllRequiredSections() {
        String output = new LocalLLM().generateCarePlan("irrelevant for this mock");
        assertContainsAllRequiredSections(output);
    }

    @Test
    @DisplayName("ClaudeService prompt instructs the model to produce all four required sections")
    void claudePromptRequestsAllRequiredSections() {
        ClaudeService service = new ClaudeService("test-key", "https://example.invalid/messages", "test-model");
        assertContainsAllRequiredSections(service.buildPrompt("irrelevant for this check"));
    }

    @Test
    @DisplayName("OpenAIService prompt instructs the model to produce all four required sections")
    void openAiPromptRequestsAllRequiredSections() {
        OpenAIService service = new OpenAIService("test-key", "https://example.invalid/chat", "test-model");
        assertContainsAllRequiredSections(service.buildPrompt("irrelevant for this check"));
    }

    private void assertContainsAllRequiredSections(String text) {
        for (String keyword : REQUIRED_SECTION_KEYWORDS) {
            assertThat(text).as("expected to find required section '%s'", keyword).contains(keyword);
        }
    }
}
