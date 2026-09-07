package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.repository.CarePlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Generates Care Plans through an LLM and retries transient failures.
 *
 * Java equivalent of the Celery implementation:
 *
 * Python Celery version:
 *   @app.task(autoretry_for=(Exception,), max_retries=3, retry_backoff=True)
 *   def generate_care_plan(careplan_id):
 *       ...
 *
 * Java Spring Retry version:
 *   @Retryable(retryFor = Exception.class, maxAttempts = 3,
 *              backoff = @Backoff(delay = 2000, multiplier = 2))
 *   public void generateCarePlan(Long carePlanId) { ... }
 *
 * Both approaches retry automatically after failures, doubling the wait time between attempts through exponential backoff.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CarePlanGenerationService {

    private static final String GENERATION_FAILURE_MESSAGE = "LLM service unavailable";

    // Business code selects the active provider through the factory instead of depending directly on OpenAI or Claude.
    private final LLMAdapterFactory llmAdapterFactory;
    private final CarePlanRepository carePlanRepository;

    /**
     * Generates a Care Plan with the LLM and automatically retries failures.
     *
     * @Retryable configuration:
     * - retryFor: Exception types that trigger a retry, here every Exception.
     * - maxAttempts: Total number of attempts, including the initial call, so this permits one initial attempt and two retries.
     * - backoff.delay: Milliseconds to wait before the first retry (2 seconds).
     * - backoff.multiplier: Factor applied to the wait time after each retry, producing exponential backoff (2 seconds, then 4, then 8).
     *
     * Equivalent Celery settings:
     *   retry_backoff=True, retry_backoff_max=10, max_retries=3
     */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void generateWithRetry(Long carePlanId) {
        // Load the CarePlan being generated.
        CarePlan carePlan = carePlanRepository.findById(carePlanId)
                .orElseThrow(() -> new RuntimeException("CarePlan not found: " + carePlanId));

        Order order = carePlan.getOrder();

        // Build the patient context supplied to the LLM.
        String patientInfo = buildPatientInfo(order);

        // Invoke the LLM. Failures here are retried by @Retryable.
        log.info("🤖 调用 LLM 生成 Care Plan... (carePlanId={})", carePlanId);
        String content = llmAdapterFactory.getService().generateCarePlan(patientInfo);

        // Persist the successfully generated plan.
        carePlan.setContent(content);
        carePlan.setStatus(CarePlan.Status.COMPLETED);
        carePlan.setErrorMessage(null);
        carePlanRepository.save(carePlan);

        log.info("✅ Care Plan 生成完成 (carePlanId={})", carePlanId);
    }

    /**
     * @Recover handles the request after all retry attempts have failed.
     *
     * The first method parameter must be the exception type; remaining parameters must match the @Retryable method.
     *
     * Equivalent Celery hook:
     *   @app.task(on_failure=handle_failure)
     */
    @Recover
    public void handleAllRetriesExhausted(Exception e, Long carePlanId) {
        log.error("❌ 重试 3 次全部失败，标记为 FAILED (carePlanId={})", carePlanId);
        log.error("   失败原因: {}", e.getMessage());

        // Mark the plan as failed so the user can resubmit it later.
        carePlanRepository.findById(carePlanId).ifPresent(carePlan -> {
            carePlan.setStatus(CarePlan.Status.FAILED);
            // Store a stable, user-safe message. The original exception can include
            // provider internals or patient data and must stay in server logs only.
            carePlan.setErrorMessage(GENERATION_FAILURE_MESSAGE);
            carePlanRepository.save(carePlan);
        });
    }

    private String buildPatientInfo(Order order) {
        return String.format("""
                Name: %s %s
                MRN: %s
                DOB: %s
                Provider: %s (NPI: %s)
                Medication: %s
                Primary Diagnosis: %s
                Additional Diagnoses: %s
                Medication History: %s
                Patient Records: %s
                """,
                order.getPatient().getFirstName(),
                order.getPatient().getLastName(),
                order.getPatient().getMrn(),
                order.getPatient().getDateOfBirth(),
                order.getProvider().getName(),
                order.getProvider().getNpi(),
                order.getMedicationName(),
                order.getPrimaryDiagnosis() != null ? order.getPrimaryDiagnosis() : "N/A",
                (order.getAdditionalDiagnoses() == null || order.getAdditionalDiagnoses().isEmpty())
                        ? "N/A" : String.join(", ", order.getAdditionalDiagnoses()),
                (order.getMedicationHistory() == null || order.getMedicationHistory().isEmpty())
                        ? "N/A" : String.join(", ", order.getMedicationHistory()),
                order.getPatientRecords() != null ? order.getPatientRecords() : "N/A"
        );
    }
}

