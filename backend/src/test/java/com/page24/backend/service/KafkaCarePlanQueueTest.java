package com.page24.backend.service;

import com.page24.backend.outbox.OutboxRepository;
import com.page24.backend.outbox.OutboxWritten;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** What KafkaCarePlanQueue writes into the outbox. The repository is a mock; no database, no Kafka. */
class KafkaCarePlanQueueTest {

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final KafkaCarePlanQueue queue =
            new KafkaCarePlanQueue(outbox, events, jsonMapper, "careplan.requested");

    @Test
    @DisplayName("enqueue writes one row: key = carePlanId, payload = exactly the five event fields, ids only")
    void writesTheEventIntoTheOutbox() {
        Instant before = Instant.now();

        queue.enqueue(42L);

        ArgumentCaptor<UUID> eventId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).insert(eventId.capture(), eq(42L), eq("careplan.requested"), eq("42"), payload.capture());

        JsonNode event = jsonMapper.readTree(payload.getValue());
        // exactly these fields: nothing about the patient or the plan may ever slip into a message
        assertThat(event.propertyNames())
                .containsExactlyInAnyOrder("schemaVersion", "eventId", "eventType", "occurredAt", "carePlanId");
        assertThat(event.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(event.get("eventId").stringValue()).isEqualTo(eventId.getValue().toString());
        assertThat(event.get("eventType").stringValue()).isEqualTo("CAREPLAN_REQUESTED");
        assertThat(event.get("carePlanId").longValue()).isEqualTo(42L);
        assertThat(Instant.parse(event.get("occurredAt").stringValue())).isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("the wake-up event is published after the row is written, and every call gets its own eventId")
    void publishesTheWakeUpAndUsesANewEventIdEachTime() {
        queue.enqueue(7L);
        queue.enqueue(7L);

        ArgumentCaptor<UUID> eventIds = ArgumentCaptor.forClass(UUID.class);
        var order = inOrder(outbox, events);
        order.verify(outbox).insert(eventIds.capture(), eq(7L), eq("careplan.requested"), eq("7"),
                org.mockito.ArgumentMatchers.anyString());
        order.verify(events).publishEvent(new OutboxWritten());
        order.verify(outbox).insert(eventIds.capture(), eq(7L), eq("careplan.requested"), eq("7"),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(eventIds.getAllValues()).doesNotHaveDuplicates();
    }
}
