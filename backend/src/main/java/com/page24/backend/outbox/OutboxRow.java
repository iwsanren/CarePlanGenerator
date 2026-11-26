package com.page24.backend.outbox;

import java.util.UUID;

/** An unsent outbox row, as the sender needs it. */
public record OutboxRow(long id, UUID eventId, long aggregateId, String topic, String messageKey, String payload) {
}
