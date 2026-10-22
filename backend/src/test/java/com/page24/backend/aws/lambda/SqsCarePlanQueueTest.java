package com.page24.backend.aws.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SQS-backed CarePlanQueue used when the app runs as a Lambda.
 * The AWS SqsClient is mocked, so no AWS account or network is involved.
 */
@ExtendWith(MockitoExtension.class)
class SqsCarePlanQueueTest {

    private static final String QUEUE_URL = "https://sqs.eu-west-1.amazonaws.com/123456789012/care-plan-queue";

    @Mock
    private SqsClient sqsClient;

    private SqsCarePlanQueue queue;

    @BeforeEach
    void setUp() {
        queue = new SqsCarePlanQueue(sqsClient, new ObjectMapper());
        // queueUrl is injected with @Value in production; here we set the field directly
        ReflectionTestUtils.setField(queue, "queueUrl", QUEUE_URL);
    }

    @Test
    @DisplayName("enqueue sends {\"carePlanId\": id} to the configured queue URL")
    void sendsMessageToConfiguredQueue() {
        // When
        queue.enqueue(42L);

        // Then: capture the request that would have gone to AWS
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(captor.getValue().messageBody()).isEqualTo("{\"carePlanId\":42}");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("enqueue fails fast when the queue URL is not configured")
    void failsWhenQueueUrlMissing(String blankUrl) {
        ReflectionTestUtils.setField(queue, "queueUrl", blankUrl);

        assertThatThrownBy(() -> queue.enqueue(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SQS_QUEUE_URL");
        verifyNoInteractions(sqsClient);
    }

    @Test
    @DisplayName("an SQS error that has AWS error details is rethrown to the caller")
    void rethrowsSqsExceptionWithDetails() {
        SqsException failure = (SqsException) SqsException.builder()
                .message("denied")
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("AccessDenied")
                        .errorMessage("not allowed")
                        .build())
                .build();
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> queue.enqueue(42L)).isSameAs(failure);
    }

    @Test
    @DisplayName("an SQS error without AWS error details is still rethrown")
    void rethrowsSqsExceptionWithoutDetails() {
        SqsException failure = (SqsException) SqsException.builder().message("boom").statusCode(500).build();
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> queue.enqueue(42L)).isSameAs(failure);
    }

    @Test
    @DisplayName("a JSON serialization failure becomes an IllegalStateException")
    void wrapsSerializationFailure() throws Exception {
        // Given: an ObjectMapper that cannot serialize the message
        ObjectMapper brokenMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });
        SqsCarePlanQueue brokenQueue = new SqsCarePlanQueue(sqsClient, brokenMapper);
        ReflectionTestUtils.setField(brokenQueue, "queueUrl", QUEUE_URL);

        // When / Then
        assertThatThrownBy(() -> brokenQueue.enqueue(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to serialize SQS message");
        verifyNoInteractions(sqsClient);
    }
}
