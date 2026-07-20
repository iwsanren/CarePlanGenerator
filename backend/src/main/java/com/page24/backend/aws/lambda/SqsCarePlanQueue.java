package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.service.CarePlanQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SqsException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

@Service
@Profile("lambda")
@RequiredArgsConstructor
@Slf4j
public class SqsCarePlanQueue implements CarePlanQueue {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.care-plan-queue-url:${SQS_QUEUE_URL:}}")
    private String queueUrl;

    @Override
    public void enqueue(Long carePlanId) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException("Missing SQS_QUEUE_URL environment variable");
        }

        String body = toJson(Map.of("carePlanId", carePlanId));
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
        } catch (SqsException ex) {
            log.error(
                    "SQS rejected SendMessage: statusCode={}, errorCode={}, errorMessage={}",
                    ex.statusCode(),
                    ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode(),
                    ex.awsErrorDetails() == null ? ex.getMessage() : ex.awsErrorDetails().errorMessage(),
                    ex
            );
            throw ex;
        }

        log.info("Sent CarePlan generation task to SQS: carePlanId={}", carePlanId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize SQS message", ex);
        }
    }
}
