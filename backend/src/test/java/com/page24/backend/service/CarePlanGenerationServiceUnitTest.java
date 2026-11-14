package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.repository.CarePlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What {@link CarePlanGenerationService} does to a care plan: the successful LLM run, the failure that must reach
 * Spring Retry, and the recovery that records a failed plan once every attempt has been used.
 * The methods are called directly, so these tests do not wait for the retry back-off.
 */
@ExtendWith(MockitoExtension.class)
class CarePlanGenerationServiceUnitTest {

    private static final long CARE_PLAN_ID = 30L;

    @Mock private LLMAdapterFactory llmAdapterFactory;
    @Mock private BaseLLMService llmService;
    @Mock private CarePlanRepository carePlanRepository;

    @InjectMocks private CarePlanGenerationService service;

    private CarePlan carePlan;

    @BeforeEach
    void setUp() {
        carePlan = new CarePlan();
        carePlan.setId(CARE_PLAN_ID);
        carePlan.setOrder(order());
        carePlan.setStatus(CarePlan.Status.PROCESSING);
        carePlan.setErrorMessage("error from an earlier attempt");
    }

    // ----- generateWithRetry -----

    @Test
    @DisplayName("LLM answers -> care plan COMPLETED with the generated content, old error cleared, saved once")
    void completesTheCarePlanWithTheGeneratedContent() {
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        when(llmAdapterFactory.getService()).thenReturn(llmService);
        when(llmService.generateCarePlan(any())).thenReturn("generated care plan");

        service.generateWithRetry(CARE_PLAN_ID);

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.COMPLETED);
        assertThat(carePlan.getContent()).isEqualTo("generated care plan");
        assertThat(carePlan.getErrorMessage()).isNull();
        verify(carePlanRepository).save(carePlan);
    }

    @Test
    @DisplayName("Success on a direct call (no retry context) -> finished_at is set and it counts as 1 attempt")
    void recordsFinishTimeAndOneAttemptOnSuccess() {
        carePlan.setStartedAt(LocalDateTime.now().minusSeconds(5));
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        when(llmAdapterFactory.getService()).thenReturn(llmService);
        when(llmService.generateCarePlan(any())).thenReturn("generated care plan");
        LocalDateTime before = LocalDateTime.now();

        service.generateWithRetry(CARE_PLAN_ID);

        assertThat(carePlan.getFinishedAt()).isNotNull().isBetween(before, LocalDateTime.now());
        assertThat(carePlan.getFinishedAt()).isAfterOrEqualTo(carePlan.getStartedAt());
        assertThat(carePlan.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Success on the 3rd attempt (two failures in the retry context) -> attempt_count 3")
    void countsTheAttemptFromTheRetryContext() {
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        when(llmAdapterFactory.getService()).thenReturn(llmService);
        when(llmService.generateCarePlan(any())).thenReturn("generated care plan");
        RetryContextSupport context = new RetryContextSupport(null);
        context.registerThrowable(new IllegalStateException("attempt 1 failed"));
        context.registerThrowable(new IllegalStateException("attempt 2 failed"));
        RetrySynchronizationManager.register(context);
        try {
            service.generateWithRetry(CARE_PLAN_ID);
        } finally {
            RetrySynchronizationManager.clear();
        }

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.COMPLETED);
        assertThat(carePlan.getAttemptCount()).isEqualTo(3);
        assertThat(carePlan.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("The LLM receives the patient context built from this care plan's order")
    void sendsThePatientContextOfTheOrderToTheLlm() {
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        when(llmAdapterFactory.getService()).thenReturn(llmService);
        when(llmService.generateCarePlan(any())).thenReturn("plan");

        service.generateWithRetry(CARE_PLAN_ID);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmService).generateCarePlan(prompt.capture());
        assertThat(prompt.getValue())
                .isEqualTo(service.buildPatientInfo(carePlan.getOrder()))
                .contains("Name: Alice Wong")
                .contains("MRN: 123456")
                .contains("Medication: IVIG");
    }

    @Test
    @DisplayName("Care plan not found -> RuntimeException naming the id; the LLM is never called, nothing saved")
    void failsWithoutCallingTheLlmWhenTheCarePlanDoesNotExist() {
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.generateWithRetry(CARE_PLAN_ID));

        assertThat(thrown).isInstanceOf(RuntimeException.class).hasMessageContaining("30");
        verifyNoInteractions(llmAdapterFactory, llmService);
        verify(carePlanRepository, never()).save(any(CarePlan.class));
    }

    @Test
    @DisplayName("LLM fails -> the exception reaches the caller (so Spring Retry can retry); plan is left unchanged")
    void letsTheLlmFailurePropagateSoThatItCanBeRetried() {
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        when(llmAdapterFactory.getService()).thenReturn(llmService);
        when(llmService.generateCarePlan(any())).thenThrow(new IllegalStateException("provider unavailable"));

        Throwable thrown = catchThrowable(() -> service.generateWithRetry(CARE_PLAN_ID));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("provider unavailable");
        // A failed attempt must neither mark the plan COMPLETED nor persist anything; the worker owns the status.
        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.PROCESSING);
        assertThat(carePlan.getContent()).isNull();
        // ... and a failed attempt is not the end of the run, so no finish time or attempt count yet.
        assertThat(carePlan.getFinishedAt()).isNull();
        assertThat(carePlan.getAttemptCount()).isNull();
        verify(carePlanRepository, never()).save(any(CarePlan.class));
    }

    // ----- handleAllRetriesExhausted -----

    @Test
    @DisplayName("Retries exhausted -> FAILED with a fixed, user-safe message that leaks nothing from the exception")
    void marksTheCarePlanFailedWithoutExposingTheExceptionDetails() {
        carePlan.setContent("partial content");
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        Exception cause = new RuntimeException("401 for patient Alice Wong MRN 123456 with key sk-secret-123");

        service.handleAllRetriesExhausted(cause, CARE_PLAN_ID);

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.FAILED);
        assertThat(carePlan.getErrorMessage())
                .isEqualTo("LLM service unavailable")
                .doesNotContain("Alice")
                .doesNotContain("123456")
                .doesNotContain("sk-secret");
        verify(carePlanRepository).save(carePlan);
    }

    @Test
    @DisplayName("Retries exhausted (3 failures in the retry context) -> FAILED with finished_at and attempt_count 3")
    void recordsFinishTimeAndAttemptCountWhenRecovering() {
        carePlan.setStartedAt(LocalDateTime.now().minusSeconds(7));
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        RetryContextSupport context = new RetryContextSupport(null);
        for (int attempt = 1; attempt <= 3; attempt++) {
            context.registerThrowable(new IllegalStateException("attempt " + attempt + " failed"));
        }
        RetrySynchronizationManager.register(context);
        LocalDateTime before = LocalDateTime.now();
        try {
            service.handleAllRetriesExhausted(new IllegalStateException("attempt 3 failed"), CARE_PLAN_ID);
        } finally {
            RetrySynchronizationManager.clear();
        }

        assertThat(carePlan.getStatus()).isEqualTo(CarePlan.Status.FAILED);
        assertThat(carePlan.getFinishedAt()).isNotNull().isBetween(before, LocalDateTime.now());
        assertThat(carePlan.getAttemptCount()).isEqualTo(3);
        verify(carePlanRepository).save(carePlan);
    }

    @Test
    @DisplayName("Recovery called directly (no retry context) -> FAILED with finished_at and 1 attempt")
    void recoveryWithoutRetryContextCountsOneAttempt() {
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));

        service.handleAllRetriesExhausted(new RuntimeException("boom"), CARE_PLAN_ID);

        assertThat(carePlan.getFinishedAt()).isNotNull();
        assertThat(carePlan.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Retries exhausted -> the log names the care plan, the exception types and the HTTP status, nothing else")
    void logsTheFailureWithoutTheExceptionMessages() {
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.of(carePlan));
        Exception cause = new RuntimeException("LLM failed for patient Alice Wong MRN 123456 key sk-secret-123",
                WebClientResponseException.create(401, "Unauthorized", HttpHeaders.EMPTY,
                        "{\"error\":\"key sk-secret-123 Alice Wong\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        try (LogCapture logs = new LogCapture(CarePlanGenerationService.class)) {
            service.handleAllRetriesExhausted(cause, CARE_PLAN_ID);

            assertThat(logs.text())
                    .contains("carePlanId=30")
                    .contains("java.lang.RuntimeException")
                    .contains("WebClientResponseException")
                    .contains("(HTTP 401)")
                    .doesNotContain("Alice")
                    .doesNotContain("123456")
                    .doesNotContain("sk-secret");
        }
    }

    @Test
    @DisplayName("Retries exhausted for an unknown care plan -> nothing saved and no exception")
    void ignoresAnUnknownCarePlanWhenRecovering() {
        when(carePlanRepository.findById(CARE_PLAN_ID)).thenReturn(Optional.empty());

        service.handleAllRetriesExhausted(new RuntimeException("boom"), CARE_PLAN_ID);

        verify(carePlanRepository, never()).save(any(CarePlan.class));
    }

    // ----- retry wiring -----

    @Test
    @DisplayName("generateWithRetry is retried up to 3 attempts with exponential back-off")
    void declaresThreeAttemptsWithExponentialBackoff() throws NoSuchMethodException {
        Retryable retryable = CarePlanGenerationService.class
                .getMethod("generateWithRetry", Long.class)
                .getAnnotation(Retryable.class);

        assertThat(retryable).isNotNull();
        assertThat(retryable.maxAttempts()).isEqualTo(3);
        assertThat(retryable.retryFor()).containsExactly(Exception.class);
        assertThat(retryable.backoff().delay()).isEqualTo(2000);
        assertThat(retryable.backoff().multiplier()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("The @Recover method takes (Exception, Long) so Spring Retry can pair it with generateWithRetry")
    void recoveryMethodMatchesTheRetriedMethodSignature() {
        List<Method> recoverMethods = Arrays.stream(CarePlanGenerationService.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Recover.class))
                .toList();

        assertThat(recoverMethods).hasSize(1);
        assertThat(recoverMethods.get(0).getName()).isEqualTo("handleAllRetriesExhausted");
        assertThat(recoverMethods.get(0).getParameterTypes()).containsExactly(Exception.class, Long.class);
    }

    // ----- helpers -----

    private static Order order() {
        Patient patient = new Patient();
        patient.setId(1L);
        patient.setFirstName("Alice");
        patient.setLastName("Wong");
        patient.setMrn("123456");
        patient.setDateOfBirth(LocalDate.of(1990, 5, 10));
        Provider provider = new Provider();
        provider.setId(2L);
        provider.setName("Dr. Green");
        provider.setNpi("1111111111");
        Order order = new Order();
        order.setId(7L);
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName("IVIG");
        order.setPrimaryDiagnosis("G70.00");
        return order;
    }
}
