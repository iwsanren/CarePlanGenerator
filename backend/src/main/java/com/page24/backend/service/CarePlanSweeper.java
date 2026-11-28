package com.page24.backend.service;

import com.page24.backend.outbox.CarePlanRequestedEvent;
import com.page24.backend.outbox.OutboxRepository;
import com.page24.backend.outbox.OutboxWritten;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * The backstop for plans that are PROCESSING and that no message is going to bring back: left by the old Redis
 * worker, or whose message is gone. (A consumer that crashes is normally recovered by Kafka redelivering its
 * uncommitted message.) For each such plan whose lease has run out it writes a new message into the outbox, once;
 * a plan already taken over as often as allowed is marked FAILED instead.
 *
 * Several instances may run: the rows are locked with SKIP LOCKED. A duplicate message would be harmless anyway,
 * only one consumer can claim the plan.
 */
@Component
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@Slf4j
public class CarePlanSweeper {

    private static final int BATCH_SIZE = 50;

    private final CarePlanClaimRepository claims;
    private final OutboxRepository outbox;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transaction;
    private final String topic;
    private final int maxReclaims;

    public CarePlanSweeper(CarePlanClaimRepository claims,
                           OutboxRepository outbox,
                           ApplicationEventPublisher events,
                           JsonMapper jsonMapper,
                           PlatformTransactionManager transactionManager,
                           @Value("${careplan.kafka.topics.requested}") String topic,
                           @Value("${careplan.claim.max-reclaims}") int maxReclaims) {
        this.claims = claims;
        this.outbox = outbox;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.topic = topic;
        this.maxReclaims = maxReclaims;
    }

    @Scheduled(fixedDelayString = "${careplan.sweeper.interval}")
    public void sweep() {
        try {
            transaction.executeWithoutResult(status ->
                    claims.lockExpired(BATCH_SIZE).forEach(this::recover));
        } catch (RuntimeException e) {
            log.warn("Sweeper run failed, trying again later: {}", FailureDescription.of(e));
        }
    }

    private void recover(CarePlanClaimRepository.ExpiredClaim expired) {
        long carePlanId = expired.carePlanId();
        if (expired.reclaimCount() >= maxReclaims) {
            // the id matches no claim: only the expired lease lets this through
            if (claims.failAbandoned(carePlanId, UUID.randomUUID(),
                    CarePlanDeadLetterRecoverer.ABANDONED_MESSAGE)) {
                log.error("Sweeper gave a care plan up after {} take-overs (carePlanId={})",
                        expired.reclaimCount(), carePlanId);
            }
            return;
        }
        UUID eventId = UUID.randomUUID();
        outbox.insert(eventId, carePlanId, topic, String.valueOf(carePlanId),
                jsonMapper.writeValueAsString(CarePlanRequestedEvent.of(eventId, Instant.now(), carePlanId)));
        claims.expireLeaseNowIfMissing(carePlanId);
        events.publishEvent(new OutboxWritten());
        log.warn("Sweeper requested a stuck care plan again (carePlanId={} eventId={})", carePlanId, eventId);
    }
}
