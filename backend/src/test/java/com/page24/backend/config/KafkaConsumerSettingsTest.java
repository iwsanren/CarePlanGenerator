package com.page24.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the relations between the consumer settings shipped in application.properties. */
class KafkaConsumerSettingsTest {

    // the retries of CarePlanLlmCaller: 3 attempts with 2 s and 4 s in between
    private static final int LLM_ATTEMPTS = 3;
    private static final Duration LLM_BACKOFF = Duration.ofSeconds(2 + 4);

    @Test
    @DisplayName("worst-case processing time < max.poll.interval.ms <= claim lease")
    void pollIntervalCoversTheWorstCaseAndTheLeaseCoversThePollInterval() throws IOException {
        Properties properties = load();
        Duration llmTimeout = duration(properties, "llm.request-timeout");
        Duration worstCase = llmTimeout.multipliedBy(LLM_ATTEMPTS).plus(LLM_BACKOFF);
        Duration pollInterval = Duration.ofMillis(Long.parseLong(
                properties.getProperty("spring.kafka.consumer.properties[max.poll.interval.ms]")));
        Duration lease = duration(properties, "careplan.claim.lease");

        assertThat(worstCase).isEqualTo(Duration.ofSeconds(186));
        assertThat(pollInterval).isGreaterThan(worstCase.plusSeconds(30)); // room for the database work
        assertThat(lease).isGreaterThanOrEqualTo(pollInterval);
    }

    @Test
    @DisplayName("one record at a time, offset committed per record after handling, from the earliest offset")
    void commitsAfterProcessing() throws IOException {
        Properties properties = load();

        assertThat(properties.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("record");
        assertThat(properties.getProperty("spring.kafka.consumer.enable-auto-commit")).isEqualTo("false");
        assertThat(properties.getProperty("spring.kafka.consumer.max-poll-records")).isEqualTo("1");
        assertThat(properties.getProperty("spring.kafka.consumer.auto-offset-reset")).isEqualTo("earliest");
        assertThat(properties.getProperty("spring.kafka.listener.concurrency")).isEqualTo("3");
        assertThat(properties.getProperty("spring.kafka.consumer.group-id")).isEqualTo("careplan-generator");
    }

    private static Properties load() throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
    }

    /** Resolves "${ENV:default}" to the default, as when the environment variable is not set. */
    private static Duration duration(Properties properties, String name) {
        MockEnvironment environment = new MockEnvironment();
        return DurationStyle.detectAndParse(environment.resolvePlaceholders(properties.getProperty(name)));
    }
}
