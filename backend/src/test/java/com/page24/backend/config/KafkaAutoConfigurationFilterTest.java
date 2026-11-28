package com.page24.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** The filter alone: which auto-configurations survive for each queue type. */
class KafkaAutoConfigurationFilterTest {

    private static final String[] CANDIDATES = {
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
            "org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            null // already removed by another filter
    };

    @Test
    @DisplayName("no queue type configured -> Kafka auto-configuration is removed, everything else stays")
    void removedByDefault() {
        assertThat(match(new MockEnvironment())).containsExactly(false, false, true, true);
    }

    @Test
    @DisplayName("queue type redis -> Kafka auto-configuration is removed")
    void removedForRedis() {
        assertThat(match(new MockEnvironment().withProperty("careplan.queue.type", "redis")))
                .containsExactly(false, false, true, true);
    }

    @Test
    @DisplayName("queue type kafka (any case, as @ConditionalOnProperty compares it) -> everything stays")
    void keptForKafka() {
        assertThat(match(new MockEnvironment().withProperty("careplan.queue.type", "KAFKA")))
                .containsExactly(true, true, true, true);
    }

    @Test
    @DisplayName("lambda profile -> removed even when the queue type says kafka")
    void removedInLambda() {
        MockEnvironment environment = new MockEnvironment().withProperty("careplan.queue.type", "kafka");
        environment.setActiveProfiles("lambda");

        assertThat(match(environment)).containsExactly(false, false, true, true);
    }

    private static boolean[] match(MockEnvironment environment) {
        KafkaAutoConfigurationFilter filter = new KafkaAutoConfigurationFilter();
        filter.setEnvironment(environment);
        return filter.match(CANDIDATES, null);
    }
}
