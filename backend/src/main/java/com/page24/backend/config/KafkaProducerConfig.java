package com.page24.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.support.ProducerListener;

/**
 * Replaces Spring's default producer listener, which logs every failed send with the record and a full stack trace.
 * The only sender is OutboxRelay, which reports failures itself, briefly and at a limited rate.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
public class KafkaProducerConfig {

    @Bean
    public ProducerListener<Object, Object> quietProducerListener() {
        return new ProducerListener<>() {
        };
    }
}
