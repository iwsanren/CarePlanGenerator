package com.page24.backend.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The two care plan topics. Spring's KafkaAdmin creates every {@link NewTopic} bean when the application starts, and
 * leaves a topic that already exists alone (it only ever adds partitions, never removes them). The broker has
 * automatic topic creation switched off, so these declarations are the only way the topics come into being.
 *
 * If Kafka cannot be reached at startup the application still starts and logs an error; the topics are then created
 * the next time it starts with Kafka available.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
public class KafkaTopicConfig {

    private final int partitions;
    private final int replicationFactor;

    public KafkaTopicConfig(@Value("${careplan.kafka.partitions}") int partitions,
                            @Value("${careplan.kafka.replication-factor}") int replicationFactor) {
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
    }

    /** "Please generate this care plan". */
    @Bean
    public NewTopic carePlanRequestedTopic(@Value("${careplan.kafka.topics.requested}") String name) {
        return topic(name);
    }

    /** Messages that can never be processed. The same layout as the source topic. */
    @Bean
    public NewTopic carePlanRequestedDeadLetterTopic(
            @Value("${careplan.kafka.topics.requested-dead-letter}") String name) {
        return topic(name);
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicationFactor).build();
    }
}
