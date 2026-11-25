package com.page24.backend.config;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Switches Spring Boot's Kafka auto-configuration off unless the Kafka queue is selected.
 *
 * Having spring-kafka on the classpath is enough for Spring Boot to create a KafkaTemplate, a KafkaAdmin and more.
 * With careplan.queue.type=redis (the default), and always in the Lambda profile, the application must behave as if
 * Kafka did not exist, so those auto-configurations are filtered out before any of their beans is defined.
 *
 * Registered in META-INF/spring.factories; it applies to every application class (local and Lambda).
 */
public class KafkaAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    private static final String KAFKA_AUTO_CONFIGURATION_PACKAGE = "org.springframework.boot.kafka.autoconfigure.";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean kafkaSelected = "kafka".equalsIgnoreCase(environment.getProperty("careplan.queue.type", "redis"))
                && !environment.acceptsProfiles(Profiles.of("lambda"));

        boolean[] keep = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            // An entry is null when another filter has already removed it.
            String name = autoConfigurationClasses[i];
            keep[i] = kafkaSelected || name == null || !name.startsWith(KAFKA_AUTO_CONFIGURATION_PACKAGE);
        }
        return keep;
    }
}
