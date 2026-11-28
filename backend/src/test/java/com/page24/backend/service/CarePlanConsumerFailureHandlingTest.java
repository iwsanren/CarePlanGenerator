package com.page24.backend.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The message parser, what the listener lets out, and what happens to a record that is given up. */
class CarePlanConsumerFailureHandlingTest {

    private static final UUID EVENT = UUID.fromString("6f1c0d2e-3b4a-4c5d-8e9f-0a1b2c3d4e5f");
    private static final String VALID = "{\"schemaVersion\":1,\"eventId\":\"" + EVENT + "\","
            + "\"eventType\":\"CAREPLAN_REQUESTED\",\"occurredAt\":\"2026-11-26T10:00:00Z\",\"carePlanId\":7}";

    private final CarePlanEventParser parser = new CarePlanEventParser(JsonMapper.builder().build());
    private final CarePlanProcessor processor = mock(CarePlanProcessor.class);
    private final CarePlanKafkaConsumer consumer = new CarePlanKafkaConsumer(parser, processor);
    private final CarePlanClaimRepository claims = mock(CarePlanClaimRepository.class);
    private final DeadLetterPublishingRecoverer publisher = mock(DeadLetterPublishingRecoverer.class);
    private final CarePlanDeadLetterRecoverer recoverer = new CarePlanDeadLetterRecoverer(parser, claims, publisher);

    @Test
    @DisplayName("a valid message reaches the processor; unknown fields are ignored")
    void passesAValidMessageOn() {
        consumer.onCarePlanRequested(record(VALID.replace("}", ",\"somethingNew\":true}")));

        verify(processor).process(EVENT, 7L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "Jane Doe, MRN 123456",
            "{}",
            "[1,2]",
            "{\"schemaVersion\":2,\"eventType\":\"CAREPLAN_REQUESTED\",\"carePlanId\":7}",
            "{\"schemaVersion\":1,\"eventType\":\"SOMETHING_ELSE\",\"carePlanId\":7}",
            "{\"schemaVersion\":1,\"eventType\":\"CAREPLAN_REQUESTED\"}",
            "{\"schemaVersion\":1,\"eventType\":\"CAREPLAN_REQUESTED\",\"carePlanId\":\"7\"}",
            "{\"schemaVersion\":1,\"eventType\":\"CAREPLAN_REQUESTED\",\"carePlanId\":7}",
            "{\"schemaVersion\":1,\"eventType\":\"CAREPLAN_REQUESTED\",\"carePlanId\":7,\"eventId\":\"Jane Doe\"}"
    })
    @DisplayName("a message that cannot be understood is an InvalidEventException that repeats nothing from it")
    void rejectsBadMessages(String value) {
        assertThatThrownBy(() -> consumer.onCarePlanRequested(record(value)))
                .isInstanceOf(InvalidEventException.class)
                .hasNoCause()
                .message().doesNotContain("Jane Doe").doesNotContain("123456");

        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("technical failure -> thrown with class names only: no message, no cause that could be logged")
    void wrapsTechnicalFailuresWithoutTheirMessage() {
        doThrow(new DataAccessResourceFailureException("Key (mrn)=(123456) of Jane Doe",
                new java.net.ConnectException("secret host")))
                .when(processor).process(EVENT, 7L);

        assertThatThrownBy(() -> consumer.onCarePlanRequested(record(VALID)))
                .isInstanceOf(TechnicalProcessingException.class)
                .hasNoCause()
                .message()
                .contains("DataAccessResourceFailureException").contains("ConnectException")
                .doesNotContain("123456").doesNotContain("Jane Doe").doesNotContain("secret");
    }

    @Test
    @DisplayName("the claim limit passes through unwrapped, so the error handler can see it must not be retried")
    void letsTheClaimLimitThrough() {
        doThrow(new ClaimLimitExceededException(7L)).when(processor).process(EVENT, 7L);

        assertThatThrownBy(() -> consumer.onCarePlanRequested(record(VALID)))
                .isInstanceOf(ClaimLimitExceededException.class);
    }

    @Test
    @DisplayName("dead letter: the plan is marked FAILED with the fixed text first, then the record is published")
    void marksThePlanBeforePublishing() {
        ConsumerRecord<String, String> record = record(VALID);
        RuntimeException cause = new TechnicalProcessingException("x");

        recoverer.accept(record, cause);

        InOrder order = inOrder(claims, publisher);
        order.verify(claims).failAbandoned(7L, EVENT, "Generation could not be completed; please regenerate");
        order.verify(publisher).accept(record, cause);
    }

    @Test
    @DisplayName("dead letter for an unreadable message: published, no database access, no new exception")
    void publishesAnUnreadableMessageWithoutTouchingTheDatabase() {
        ConsumerRecord<String, String> record = record("not json");

        assertThatCode(() -> recoverer.accept(record, new InvalidEventException("x"))).doesNotThrowAnyException();

        verifyNoInteractions(claims);
        verify(publisher).accept(any(), any());
    }

    @Test
    @DisplayName("dead letter for an id that does not exist: nothing is updated, published, no exception")
    void publishesWhenThePlanDoesNotExist() {
        // failAbandoned answers false for a plan that is not there
        assertThatCode(() -> recoverer.accept(record(VALID), new TechnicalProcessingException("x")))
                .doesNotThrowAnyException();

        verify(publisher).accept(any(), any());
    }

    @Test
    @DisplayName("dead letter while the database is down: it throws (sanitized) and does not publish, so Kafka retries")
    void doesNotGiveUpWhileTheOutcomeCannotBeRecorded() {
        doThrow(new DataAccessResourceFailureException("Jane Doe"))
                .when(claims).failAbandoned(anyLong(), any(), anyString());

        assertThatThrownBy(() -> recoverer.accept(record(VALID), new TechnicalProcessingException("x")))
                .isInstanceOf(TechnicalProcessingException.class)
                .hasNoCause()
                .message().doesNotContain("Jane Doe");

        verify(publisher, never()).accept(any(), any());
    }

    @Test
    @DisplayName("the recoverer's log has position and ids, never the message or the exception's text")
    void logsNoContent() {
        try (LogCapture logs = new LogCapture(CarePlanDeadLetterRecoverer.class)) {
            recoverer.accept(record("Jane Doe 123456"), new IllegalStateException("Jane Doe 123456"));

            assertThat(logs.text()).contains("careplan.requested").contains("unreadable")
                    .doesNotContain("Jane Doe").doesNotContain("123456");
        }
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("careplan.requested", 0, 5L, "7", value);
    }
}
