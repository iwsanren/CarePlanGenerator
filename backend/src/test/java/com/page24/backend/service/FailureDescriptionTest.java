package com.page24.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FailureDescriptionTest {

    @Test
    @DisplayName("A plain exception is described by its type only, never by its message")
    void describesTheTypeOnly() {
        String text = FailureDescription.of(new IllegalStateException("MRN 123456 for Jane Smith"));

        assertThat(text).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    @DisplayName("The types along the chain of causes are listed from the outside in")
    void listsTheChainOfCauses() {
        Exception failure = new RuntimeException("wrapper secret",
                new IllegalArgumentException("inner secret", new java.io.IOException("root secret")));

        String text = FailureDescription.of(failure);

        assertThat(text).isEqualTo(
                "java.lang.RuntimeException <- java.lang.IllegalArgumentException <- java.io.IOException");
        assertThat(text).doesNotContain("secret");
    }

    @Test
    @DisplayName("An HTTP answer from the provider adds its status code, not its body")
    void addsTheHttpStatusOfAProviderAnswer() {
        WebClientResponseException unauthorized = WebClientResponseException.create(
                401, "Unauthorized", HttpHeaders.EMPTY,
                "{\"error\":\"Incorrect API key provided: sk-leaky\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        String text = FailureDescription.of(new RuntimeException("failed", unauthorized));

        assertThat(text).startsWith("java.lang.RuntimeException <- ").endsWith(" (HTTP 401)");
        assertThat(text).contains("WebClientResponseException").doesNotContain("sk-leaky");
    }

    @Test
    @DisplayName("A chain of causes that loops back on itself still ends")
    void endsOnACircularChain() {
        Exception first = new RuntimeException("first");
        Exception second = new RuntimeException("second", first);
        first.initCause(second);

        String text = FailureDescription.of(first);

        assertThat(text.split(" <- ")).hasSize(10);
    }
}
