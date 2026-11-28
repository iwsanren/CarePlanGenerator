package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Generates one care plan for one message, in three stages:
 * <ol>
 *   <li>claim the plan and read what the prompt needs (one short transaction);</li>
 *   <li>call the LLM, with no transaction open;</li>
 *   <li>write the result with the claim token.</li>
 * </ol>
 * Returning normally means "this message is done with", whether the plan was completed, failed because the LLM gave
 * up, or was skipped. An exception means the opposite. A failure in stage 1 or 3 is technical and is retried; the
 * retry of the same message may take the plan over again, and generates again.
 */
@Service
@Profile("!lambda")
@ConditionalOnProperty(name = "careplan.queue.type", havingValue = "kafka")
@Slf4j
public class CarePlanProcessor {

    static final String GENERATION_FAILURE_MESSAGE = "LLM service unavailable";

    private final CarePlanClaimRepository claims;
    private final CarePlanRepository carePlanRepository;
    private final CarePlanGenerationService generationService;
    private final CarePlanLlmCaller llm;
    private final TransactionTemplate transaction;
    private final Duration lease;
    private final int maxReclaims;

    public CarePlanProcessor(CarePlanClaimRepository claims,
                             CarePlanRepository carePlanRepository,
                             CarePlanGenerationService generationService,
                             CarePlanLlmCaller llm,
                             PlatformTransactionManager transactionManager,
                             @Value("${careplan.claim.lease}") Duration lease,
                             @Value("${careplan.claim.max-reclaims}") int maxReclaims) {
        this.claims = claims;
        this.carePlanRepository = carePlanRepository;
        this.generationService = generationService;
        this.llm = llm;
        this.transaction = new TransactionTemplate(transactionManager);
        this.lease = lease;
        this.maxReclaims = maxReclaims;
    }

    public void process(UUID eventId, long carePlanId) {
        Claim claim = transaction.execute(status -> claim(eventId, carePlanId));
        if (claim.limitReached()) {
            // thrown after the transaction, so that FAILED is committed before the message is dead-lettered
            throw new ClaimLimitExceededException(carePlanId);
        }
        if (claim.patientInfo() == null) {
            return;
        }

        CarePlanLlmCaller.Outcome outcome = llm.generate(carePlanId, claim.patientInfo());

        boolean written = outcome.gaveUp()
                ? claims.fail(carePlanId, claim.token(), GENERATION_FAILURE_MESSAGE, outcome.attempts())
                : claims.complete(carePlanId, claim.token(), outcome.content(), outcome.attempts());
        if (written) {
            log.info("Care plan {} (carePlanId={} eventId={})",
                    outcome.gaveUp() ? "FAILED" : "COMPLETED", carePlanId, eventId);
        } else {
            // somebody else took the plan over while this consumer was working: its result is thrown away
            log.warn("Claim lost, result discarded (carePlanId={} eventId={} claimToken={})",
                    carePlanId, eventId, claim.token());
        }
    }

    private Claim claim(UUID eventId, long carePlanId) {
        OptionalInt token = claims.claim(carePlanId, eventId, lease, maxReclaims);
        if (token.isPresent()) {
            CarePlan carePlan = carePlanRepository.findById(carePlanId)
                    .orElseThrow(() -> new IllegalStateException("Claimed care plan disappeared"));
            return new Claim(token.getAsInt(), generationService.buildPatientInfo(carePlan.getOrder()), false);
        }

        CarePlanClaimRepository.ClaimState state = claims.stateOf(carePlanId, eventId);
        if (state == null) {
            log.warn("No such care plan, message skipped (carePlanId={} eventId={})", carePlanId, eventId);
            return Claim.SKIP;
        }
        if (CarePlan.Status.COMPLETED.name().equals(state.status())
                || CarePlan.Status.FAILED.name().equals(state.status())) {
            log.info("Care plan already {}, duplicate skipped (carePlanId={} eventId={})",
                    state.status(), carePlanId, eventId);
            return Claim.SKIP;
        }
        if (CarePlan.Status.PROCESSING.name().equals(state.status())) {
            if (!state.open()) {
                log.info("Care plan is being generated for another message, skipped (carePlanId={} eventId={})",
                        carePlanId, eventId);
                return Claim.SKIP;
            }
            if (state.reclaimCount() >= maxReclaims) {
                claims.failAbandoned(carePlanId, eventId, CarePlanDeadLetterRecoverer.ABANDONED_MESSAGE);
                return Claim.LIMIT;
            }
        }
        // the plan changed between the two statements: try again
        throw new IllegalStateException("Care plan changed while it was being claimed");
    }

    private record Claim(int token, String patientInfo, boolean limitReached) {

        private static final Claim SKIP = new Claim(0, null, false);
        private static final Claim LIMIT = new Claim(0, null, true);
    }
}
