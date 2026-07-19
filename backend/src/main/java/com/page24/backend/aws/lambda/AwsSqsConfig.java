package com.page24.backend.aws.lambda;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@Profile("lambda")
public class AwsSqsConfig {

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.create();
    }
}
