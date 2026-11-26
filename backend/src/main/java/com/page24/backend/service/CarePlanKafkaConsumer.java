package com.page24.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes careplan.requested. The offset of a record is committed only after this method has returned for it (ack
 * mode RECORD, auto commit off), so a record whose handling did not finish is delivered again: at least once.
 *
 * It is the counterpart of CarePlanWorker, which only exists with careplan.queue.type=redis; exactly one of the two
 * is ever active in one application.
 */
@Component
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class CarePlanKafkaConsumer {

    private final CarePlanEventParser parser;
    private final CarePlanProcessor processor;

    @KafkaListener(topics = "${careplan.kafka.topics.requested}")
    public void onCarePlanRequested(ConsumerRecord<String, String> record) {
        CarePlanEventParser.Message message = parser.parse(record.value());
        try {
            processor.process(message.eventId(), message.carePlanId());
        } catch (ClaimLimitExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            // only class names travel on: see TechnicalProcessingException. This line is the sign of a retry, since
            // Spring's own logging of failed records is switched down.
            String types = FailureDescription.of(e);
            log.warn("Care plan processing failed, the message will be retried (carePlanId={} eventId={}): {}",
                    message.carePlanId(), message.eventId(), types);
            throw new TechnicalProcessingException(types);
        }
    }
}
