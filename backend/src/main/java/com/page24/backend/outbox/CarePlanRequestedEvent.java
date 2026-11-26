package com.page24.backend.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * "Please generate this care plan", as it goes on the wire (JSON, no Java type information). It carries ids only:
 * no patient data, no plan content, no error text. Consumers must ignore fields they do not know; a field is only
 * removed or changes meaning together with a new {@code schemaVersion}.
 */
public record CarePlanRequestedEvent(
        int schemaVersion, String eventId, String eventType, String occurredAt, long carePlanId) {

    public static final int SCHEMA_VERSION = 1;
    public static final String TYPE = "CAREPLAN_REQUESTED";

    public static CarePlanRequestedEvent of(UUID eventId, Instant occurredAt, long carePlanId) {
        return new CarePlanRequestedEvent(
                SCHEMA_VERSION, eventId.toString(), TYPE, occurredAt.toString(), carePlanId);
    }
}
