package com.page24.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

/**
 * What happens to a record that is given up: mark its care plan FAILED (fixed text), then publish the record to the
 * dead-letter topic.
 *
 * If the care plan cannot be marked because of a technical failure, this throws, and Spring Kafka delivers the record
 * again: the consumer must not move on while it cannot record the outcome. A message that cannot be read has no care
 * plan to mark and is only published.
 */
@Component
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@Slf4j
public class CarePlanDeadLetterRecoverer implements ConsumerRecordRecoverer {

    static final String ABANDONED_MESSAGE = "Generation could not be completed; please regenerate";

    private final CarePlanEventParser parser;
    private final CarePlanClaimRepository claims;
    private final DeadLetterPublishingRecoverer deadLetterPublisher;

    public CarePlanDeadLetterRecoverer(CarePlanEventParser parser,
                                       CarePlanClaimRepository claims,
                                       DeadLetterPublishingRecoverer deadLetterPublisher) {
        this.parser = parser;
        this.claims = claims;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        CarePlanEventParser.Message message = readable(record);
        if (message != null) {
            try {
                claims.failAbandoned(message.carePlanId(), message.eventId(), ABANDONED_MESSAGE);
            } catch (RuntimeException e) {
                throw new TechnicalProcessingException(FailureDescription.of(e));
            }
        }
        deadLetterPublisher.accept(record, exception);
        // position and ids only: neither the value nor the exception's message belongs in a log
        log.error("Message sent to the dead-letter topic (topic={} partition={} offset={} carePlanId={} reason={})",
                record.topic(), record.partition(), record.offset(),
                message == null ? "unreadable" : message.carePlanId(), FailureDescription.of(exception));
    }

    private CarePlanEventParser.Message readable(ConsumerRecord<?, ?> record) {
        try {
            return parser.parse(record.value() instanceof String value ? value : null);
        } catch (InvalidEventException e) {
            return null;
        }
    }
}
