package com.page24.backend.config;

import com.page24.backend.service.CarePlanDeadLetterRecoverer;
import com.page24.backend.service.ClaimLimitExceededException;
import com.page24.backend.service.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Error handling of the consumer. Without it Spring Kafka retries ten times without pause and then only logs the
 * record, which silently drops it.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
public class KafkaConsumerConfig {

    /** 1, 2, 4, 8 and 16 seconds: five retries, about half a minute. */
    static final long FIRST_RETRY_MS = 1000;
    static final long MAX_RETRIES = 5;

    /**
     * Publishes to the dead-letter topic by name (the library's default name has changed between versions) without
     * choosing a partition. The exception message and stack trace headers are left out: they can carry data. A failed
     * publish throws, so the record is not committed as if it had been saved.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${careplan.kafka.topics.requested-dead-letter}") String deadLetterTopic) {
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(
                kafka, (record, exception) -> new TopicPartition(deadLetterTopic, -1));
        publisher.setFailIfSendResultIsError(true);
        publisher.excludeHeader(
                DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_MSG,
                DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        return publisher;
    }

    /** Spring Boot gives this error handler to the listener containers. */
    @Bean
    public DefaultErrorHandler carePlanErrorHandler(CarePlanDeadLetterRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff(FIRST_RETRY_MS, 2);
        backOff.setMaxAttempts(MAX_RETRIES);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(InvalidEventException.class, ClaimLimitExceededException.class);
        // Spring would log every failure with the whole record; the listener and the recoverer log what is needed.
        errorHandler.setLogLevel(KafkaException.Level.DEBUG);
        return errorHandler;
    }
}
