package com.page24.backend.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the timeouts shipped in application.properties. If the producer could still be trying when the sender has
 * already recorded a failure, the message could arrive afterwards and then be sent a second time.
 */
class OutboxProducerTimeoutsTest {

    @Test
    @DisplayName("the producer gives up (delivery.timeout.ms) before the sender stops waiting (send-timeout)")
    void producerGivesUpBeforeTheSender() throws IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        Duration delivery = milliseconds(properties, "delivery.timeout.ms");
        Duration request = milliseconds(properties, "request.timeout.ms");
        Duration senderWaits = DurationStyle.detectAndParse(
                properties.getProperty("careplan.outbox.send-timeout"));

        assertThat(delivery).isLessThan(senderWaits);
        // Kafka's own rule (delivery >= linger + request); linger is 5 ms by default
        assertThat(delivery).isGreaterThanOrEqualTo(request.plusMillis(5));
        assertThat(properties.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
        assertThat(properties.getProperty("spring.kafka.producer.properties[enable.idempotence]"))
                .isEqualTo("true");
    }

    private static Duration milliseconds(Properties properties, String name) {
        return Duration.ofMillis(Long.parseLong(
                properties.getProperty("spring.kafka.producer.properties[" + name + "]")));
    }
}
