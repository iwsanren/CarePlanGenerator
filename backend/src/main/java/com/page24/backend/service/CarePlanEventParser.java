package com.page24.backend.service;

import com.page24.backend.outbox.CarePlanRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Reads a message of careplan.requested. The wire format is a plain JSON string without Java type information, so
 * nothing a message says can choose which class is created. Unknown fields are ignored. Anything that cannot be
 * understood is an {@link InvalidEventException}, which carries no part of the message.
 */
@Component
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@RequiredArgsConstructor
public class CarePlanEventParser {

    private final JsonMapper jsonMapper;

    public Message parse(String value) {
        if (value == null) {
            throw new InvalidEventException("empty message");
        }
        JsonNode event;
        try {
            event = jsonMapper.readTree(value);
        } catch (JacksonException e) {
            // the parser's own message quotes the input, so it is not passed on
            throw new InvalidEventException("not readable JSON");
        }
        JsonNode version = event.path("schemaVersion");
        if (!version.isIntegralNumber() || version.intValue() != CarePlanRequestedEvent.SCHEMA_VERSION) {
            throw new InvalidEventException("unknown or missing schemaVersion");
        }
        JsonNode type = event.path("eventType");
        if (!type.isString() || !CarePlanRequestedEvent.TYPE.equals(type.stringValue())) {
            throw new InvalidEventException("unknown or missing eventType");
        }
        JsonNode carePlanId = event.path("carePlanId");
        if (!carePlanId.isIntegralNumber() || carePlanId.longValue() <= 0) {
            throw new InvalidEventException("missing carePlanId");
        }
        return new Message(eventId(event.path("eventId")), carePlanId.longValue());
    }

    private static UUID eventId(JsonNode node) {
        if (node.isString()) {
            try {
                return UUID.fromString(node.stringValue());
            } catch (IllegalArgumentException e) {
                throw new InvalidEventException("eventId is not a UUID");
            }
        }
        throw new InvalidEventException("missing eventId");
    }

    /** What the consumer needs from a message. */
    public record Message(UUID eventId, long carePlanId) {
    }
}
