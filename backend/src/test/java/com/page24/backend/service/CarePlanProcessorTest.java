package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.service.CarePlanClaimRepository.ClaimState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the processor does with each answer of the claim, and which failures it lets out. The SQL conditions behind
 * those answers are tested against PostgreSQL in CarePlanClaimPostgresTest.
 */
class CarePlanProcessorTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final Duration LEASE = Duration.ofMinutes(6);

    private final CarePlanClaimRepository claims = mock(CarePlanClaimRepository.class);
    private final CarePlanRepository carePlans = mock(CarePlanRepository.class);
    private final CarePlanGenerationService generation = mock(CarePlanGenerationService.class);
    private final CarePlanLlmCaller llm = mock(CarePlanLlmCaller.class);
    private final CarePlanProcessor processor = new CarePlanProcessor(
            claims, carePlans, generation, llm, mock(PlatformTransactionManager.class), LEASE, 2);

    @Test
    @DisplayName("claimed and generated -> COMPLETED is written with the claim token and the attempt count")
    void completesAClaimedPlan() {
        claimSucceeds(3);
        when(llm.generate(7L, "prompt")).thenReturn(new CarePlanLlmCaller.Outcome("the plan", 2));
        when(claims.complete(7L, 3, "the plan", 2)).thenReturn(true);

        processor.process(EVENT, 7L);

        verify(claims).complete(7L, 3, "the plan", 2);
        verify(claims, never()).fail(anyLong(), anyInt(), anyString(), anyInt());
    }

    @Test
    @DisplayName("business failure: the LLM gave up -> FAILED is written and nothing is thrown (the message is done)")
    void businessFailureIsNotThrown() {
        claimSucceeds(1);
        when(llm.generate(7L, "prompt")).thenReturn(new CarePlanLlmCaller.Outcome(null, 3));
        when(claims.fail(7L, 1, "LLM service unavailable", 3)).thenReturn(true);

        assertThatCode(() -> processor.process(EVENT, 7L)).doesNotThrowAnyException();

        verify(claims).fail(7L, 1, "LLM service unavailable", 3);
    }

    @Test
    @DisplayName("claim lost while generating -> the late result is discarded without an error")
    void discardsTheResultWhenTheClaimWasLost() {
        claimSucceeds(1);
        when(llm.generate(7L, "prompt")).thenReturn(new CarePlanLlmCaller.Outcome("late", 1));
        when(claims.complete(7L, 1, "late", 1)).thenReturn(false);

        assertThatCode(() -> processor.process(EVENT, 7L)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED"})
    @DisplayName("already finished -> skipped, no LLM call, no error (duplicate delivery)")
    void skipsFinishedPlans(String status) {
        claimFails(new ClaimState(status, 0, true));

        assertThatCode(() -> processor.process(EVENT, 7L)).doesNotThrowAnyException();

        verifyNoInteractions(llm);
    }

    @Test
    @DisplayName("PROCESSING under another message's live lease -> skipped, nothing is changed")
    void skipsAPlanSomebodyElseIsGenerating() {
        claimFails(new ClaimState("PROCESSING", 0, false));

        processor.process(EVENT, 7L);

        verifyNoInteractions(llm);
        verify(claims, never()).failAbandoned(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("no such care plan -> skipped")
    void skipsAnUnknownPlan() {
        claimFails(null);

        processor.process(EVENT, 7L);

        verifyNoInteractions(llm);
    }

    @Test
    @DisplayName("claim limit reached -> the plan is marked FAILED, then ClaimLimitExceededException")
    void givesUpAtTheClaimLimit() {
        claimFails(new ClaimState("PROCESSING", 2, true));

        assertThatThrownBy(() -> processor.process(EVENT, 7L)).isInstanceOf(ClaimLimitExceededException.class);

        verify(claims).failAbandoned(7L, EVENT, "Generation could not be completed; please regenerate");
        verifyNoInteractions(llm);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING"})
    @DisplayName("the plan changed between claim and read -> an error, so the message is tried again")
    void retriesWhenThePlanChangedUnderfoot(String status) {
        claimFails(new ClaimState(status, 0, true));

        assertThatThrownBy(() -> processor.process(EVENT, 7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("technical failure: the database is unavailable -> the exception comes out, no LLM call")
    void technicalFailureOnClaimIsThrown() {
        when(claims.claim(7L, EVENT, LEASE, 2)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> processor.process(EVENT, 7L))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verifyNoInteractions(llm);
    }

    @Test
    @DisplayName("technical failure: writing the result fails -> thrown, and the plan is not marked FAILED")
    void technicalFailureOnWriteIsNotABusinessFailure() {
        claimSucceeds(1);
        when(llm.generate(7L, "prompt")).thenReturn(new CarePlanLlmCaller.Outcome("the plan", 1));
        when(claims.complete(7L, 1, "the plan", 1)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> processor.process(EVENT, 7L))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(claims, never()).fail(anyLong(), anyInt(), anyString(), anyInt());
    }

    private void claimSucceeds(int token) {
        CarePlan carePlan = new CarePlan();
        Order order = new Order();
        carePlan.setOrder(order);
        when(claims.claim(7L, EVENT, LEASE, 2)).thenReturn(OptionalInt.of(token));
        when(carePlans.findById(7L)).thenReturn(Optional.of(carePlan));
        when(generation.buildPatientInfo(order)).thenReturn("prompt");
    }

    private void claimFails(ClaimState state) {
        when(claims.claim(7L, EVENT, LEASE, 2)).thenReturn(OptionalInt.empty());
        when(claims.stateOf(7L, EVENT)).thenReturn(state);
    }
}
