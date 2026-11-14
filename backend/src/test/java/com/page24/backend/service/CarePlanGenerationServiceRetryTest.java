package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.repository.CarePlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CarePlanGenerationService} behind the real Spring Retry proxy (the same @EnableRetry wiring as the app),
 * so {@code attempt_count} is proven against what Spring Retry really does, not against a hand-built context.
 *
 * These tests wait for the real back-off (2 s after the first failure, 4 s after the second), so the whole class
 * takes about 12 seconds. Only the LLM and the repository are mocks.
 */
@SpringJUnitConfig(CarePlanGenerationServiceRetryTest.RetryConfig.class)
class CarePlanGenerationServiceRetryTest {

    private static final long CARE_PLAN_ID = 30L;

    @Configuration
    @EnableRetry
    @Import(CarePlanGenerationService.class)
    static class RetryConfig {
    }

    @MockitoBean private LLMAdapterFactory llmAdapterFactory;
    @MockitoBean private BaseLLMService llmService;
    @MockitoBean private CarePlanRepository carePlanRepository;

    @Autowired private CarePlanGenerationService service;

    private CarePlan carePlan;
    private LocalDateTime startedAt;

    @BeforeEach
    void setUp() {
        Mockito.reset(llmAdapterFactory, llmService, carePlanRepository);
        carePlan = new CarePlan();
        carePlan.setId(CARE_PLAN_ID);
        carePlan.setOrder(order());
        // The state the worker leaves behind before it calls the service.
        startedAt = LocalDateTime.now();
        carePlan.markProcessing(startedAt);
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        when(llmAdapterFactory.getService()).thenReturn(llmService);
    }

    @Test
    @DisplayName("LLM answers at once -> COMPLETED, attempt_count 1, one LLM call")
    void succeedsOnTheFirstAttempt() {
        when(llmService.generateCarePlan(any())).thenReturn("plan");

        service.generateWithRetry(CARE_PLAN_ID);

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.COMPLETED);
        assertThat(carePlan.getAttemptCount()).isEqualTo(1);
        assertThat(carePlan.getFinishedAt()).isNotNull();
        verify(llmService, times(1)).generateCarePlan(any());
    }

    @Test
    @DisplayName("Two failures then success -> COMPLETED, attempt_count 3, and the run time includes the 2 s + 4 s back-off")
    void succeedsOnTheThirdAttempt() {
        when(llmService.generateCarePlan(any()))
                .thenThrow(new IllegalStateException("first failure"))
                .thenThrow(new IllegalStateException("second failure"))
                .thenReturn("plan");

        service.generateWithRetry(CARE_PLAN_ID);

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.COMPLETED);
        assertThat(carePlan.getContent()).isEqualTo("plan");
        assertThat(carePlan.getAttemptCount()).isEqualTo(3);
        assertThat(carePlan.getFinishedAt()).isNotNull();
        // finished_at - started_at is wall-clock time: it contains both back-off sleeps (6 s in total).
        assertThat(Duration.between(startedAt, carePlan.getFinishedAt()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(5900));
        verify(llmService, times(3)).generateCarePlan(any());
    }

    @Test
    @DisplayName("Three failures -> FAILED through @Recover, attempt_count 3, finished_at set, no more LLM calls")
    void failsAfterThreeAttempts() {
        when(llmService.generateCarePlan(any())).thenThrow(new IllegalStateException("provider down"));

        service.generateWithRetry(CARE_PLAN_ID);

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.FAILED);
        assertThat(carePlan.getErrorMessage()).isEqualTo("LLM service unavailable");
        assertThat(carePlan.getAttemptCount()).isEqualTo(3);
        assertThat(carePlan.getFinishedAt()).isNotNull();
        assertThat(Duration.between(startedAt, carePlan.getFinishedAt()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(5900));
        verify(llmService, times(3)).generateCarePlan(any());
    }

    private static Order order() {
        Patient patient = new Patient();
        patient.setFirstName("Alice");
        patient.setLastName("Wong");
        patient.setMrn("123456");
        Provider provider = new Provider();
        provider.setName("Dr. Green");
        provider.setNpi("1111111111");
        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        return order;
    }
}
