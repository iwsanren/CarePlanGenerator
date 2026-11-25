package com.page24.backend.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Placeholder for the Kafka queue (careplan.queue.type=kafka). It lets the application start in kafka mode while
 * the producer (task CP-C3) does not exist yet.
 *
 * <b>It sends nothing.</b> An order is saved and its care plan stays PENDING for good: the real producer will not
 * pick such plans up later either, and regeneration is refused for a PENDING plan, so the only ways out are a
 * manual upload or a new order. It says so loudly in the log instead of failing the order.
 */
@Service
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@Slf4j
public class KafkaCarePlanQueue implements CarePlanQueue {

    @PostConstruct
    void warnThatKafkaModeIsIncomplete() {
        log.warn("careplan.queue.type=kafka: the Kafka producer is not implemented yet. "
                + "Care plans created now will NOT be generated. Use careplan.queue.type=redis for normal use.");
    }

    @Override
    public void enqueue(Long carePlanId) {
        log.warn("Care plan NOT queued (Kafka producer not implemented): carePlanId={} stays PENDING", carePlanId);
    }
}
