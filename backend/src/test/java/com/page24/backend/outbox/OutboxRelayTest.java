package com.page24.backend.outbox;

import com.page24.backend.service.LogCapture;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sender's success and failure branches. Repository, Kafka and transaction manager are mocks. */
class OutboxRelayTest {

    private static final String TOPIC = "careplan.requested";

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxRelay relay = new OutboxRelay(
            outbox, kafka, mock(PlatformTransactionManager.class), 2, Duration.ofMillis(200));

    @Test
    @DisplayName("every acknowledged row is marked sent, in order, each only after its own acknowledgement")
    void marksRowsSentAfterTheyAreAcknowledged() {
        when(outbox.lockUnsent(2)).thenReturn(List.of(row(1)));
        when(kafka.send(TOPIC, "101", "payload-1")).thenReturn(acknowledged());

        relay.drain();

        InOrder order = inOrder(kafka, outbox);
        order.verify(kafka).send(TOPIC, "101", "payload-1");
        order.verify(outbox).markSent(1);
        verify(outbox, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("a full batch is followed by the next one until the outbox is empty")
    void keepsGoingWhileBatchesAreFull() {
        when(outbox.lockUnsent(2)).thenReturn(List.of(row(1), row(2)), List.of(row(3)));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acknowledged());

        relay.drain();

        verify(outbox, times(2)).lockUnsent(2);
        verify(outbox).markSent(3);
    }

    @Test
    @DisplayName("a refused send: the row is marked failed with the root exception's class name, and the batch stops")
    void stopsTheBatchAtTheFirstFailure() {
        when(outbox.lockUnsent(2)).thenReturn(List.of(row(1), row(2)));
        when(kafka.send(TOPIC, "101", "payload-1")).thenReturn(CompletableFuture.failedFuture(
                new KafkaException("send failed", new TimeoutException("patient Jane Doe in a message"))));

        relay.drain();

        verify(outbox).markFailed(1, "TimeoutException");
        verify(outbox, never()).markSent(anyLong());
        verify(kafka, times(1)).send(anyString(), anyString(), anyString()); // row 2 was not tried
        verify(outbox, times(1)).lockUnsent(2); // and no further batch
    }

    @Test
    @DisplayName("rows sent before the failure stay sent")
    void keepsWhatWasAlreadySent() {
        when(outbox.lockUnsent(2)).thenReturn(List.of(row(1), row(2)));
        when(kafka.send(TOPIC, "101", "payload-1")).thenReturn(acknowledged());
        when(kafka.send(TOPIC, "102", "payload-2")).thenThrow(new KafkaException("no metadata"));

        relay.drain();

        verify(outbox).markSent(1);
        verify(outbox).markFailed(2, "KafkaException");
        verify(outbox, never()).markSent(2);
    }

    @Test
    @DisplayName("no acknowledgement within the send timeout counts as a failure")
    void givesUpWaitingAfterTheSendTimeout() {
        when(outbox.lockUnsent(2)).thenReturn(List.of(row(1)));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());

        relay.drain();

        verify(outbox).markFailed(1, "TimeoutException");
        verify(outbox, never()).markSent(anyLong());
    }

    @Test
    @DisplayName("an interrupted wait marks nothing and keeps the interrupt flag")
    void stopsQuietlyWhenInterrupted() {
        when(outbox.lockUnsent(2)).thenReturn(List.of(row(1)));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        relay.drain();

        assertThat(Thread.interrupted()).isTrue(); // also clears it for the other tests
        verify(outbox, never()).markSent(anyLong());
        verify(outbox, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("a database failure is not thrown to the scheduler, and its message is not logged")
    void survivesADatabaseFailure() {
        when(outbox.lockUnsent(2)).thenThrow(new DataAccessResourceFailureException("db says: Jane Doe"));

        try (LogCapture logs = new LogCapture(SendFailureLog.class)) {
            assertThatCode(relay::poll).doesNotThrowAnyException();

            assertThat(logs.text()).contains("DataAccessResourceFailureException").doesNotContain("Jane Doe");
        }
        verify(kafka, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("repeated failures produce one warning, without exception message or stack trace")
    void doesNotLogEveryFailure() {
        when(outbox.lockUnsent(2)).thenReturn(List.of(row(1)));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(
                new TimeoutException("secret detail")));

        try (LogCapture logs = new LogCapture(SendFailureLog.class)) {
            for (int run = 0; run < 20; run++) {
                relay.drain();
            }

            assertThat(logs.text().lines()).hasSize(1);
            assertThat(logs.text()).contains("TimeoutException")
                    .doesNotContain("secret detail").doesNotContain("\tat ");
        }
    }

    private static OutboxRow row(long id) {
        return new OutboxRow(id, UUID.randomUUID(), 100 + id, TOPIC, String.valueOf(100 + id), "payload-" + id);
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }
}
