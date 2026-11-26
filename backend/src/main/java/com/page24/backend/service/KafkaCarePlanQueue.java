package com.page24.backend.service;

import com.page24.backend.outbox.CarePlanRequestedEvent;
import com.page24.backend.outbox.OutboxRepository;
import com.page24.backend.outbox.OutboxWritten;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * The Kafka queue (careplan.queue.type=kafka). It does not talk to Kafka: it writes the message into the outbox in
 * the caller's transaction, so the message exists exactly when the care plan does. OutboxRelay sends it after the
 * commit, and CarePlanKafkaConsumer generates the plan.
 */
@Service
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@Slf4j
public class KafkaCarePlanQueue implements CarePlanQueue {

    private final OutboxRepository outbox;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;
    private final String topic;

    public KafkaCarePlanQueue(OutboxRepository outbox,
                              ApplicationEventPublisher events,
                              JsonMapper jsonMapper,
                              @Value("${careplan.kafka.topics.requested}") String topic) {
        this.outbox = outbox;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
    }

    @PostConstruct
    void announceTheQueueInUse() {
        log.info("careplan.queue.type=kafka: care plans are queued through the outbox to topic {}", topic);
    }

    /** MANDATORY: without the caller's transaction the row could be committed although the care plan is not. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Long carePlanId) {
        UUID eventId = UUID.randomUUID();
        String payload = jsonMapper.writeValueAsString(
                CarePlanRequestedEvent.of(eventId, Instant.now(), carePlanId));
        outbox.insert(eventId, carePlanId, topic, String.valueOf(carePlanId), payload);
        events.publishEvent(new OutboxWritten());
        log.info("Care plan written to the outbox: carePlanId={} eventId={}", carePlanId, eventId);
    }
}
