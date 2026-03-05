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
 * Care Plan 生成服务 - 负责调 LLM，并支持失败重试
 *
 * 这里是 Celery 对应的 Java 实现方式：
 *
 * Python Celery 写法：
 *   @app.task(autoretry_for=(Exception,), max_retries=3, retry_backoff=True)
 *   def generate_care_plan(careplan_id):
 *       ...
 *
 * Java Spring Retry 写法：
 *   @Retryable(retryFor = Exception.class, maxAttempts = 3,
 *              backoff = @Backoff(delay = 2000, multiplier = 2))
 *   public void generateCarePlan(Long carePlanId) { ... }
 *
 * 效果完全一样：失败了自动重试，每次等待时间翻倍（指数退避）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CarePlanGenerationService {

    private final LLMService llmService;
    private final CarePlanRepository carePlanRepository;

    /**
     * 调用 LLM 生成 Care Plan，失败时自动重试
     *
     * @Retryable 参数说明：
     * - retryFor: 遇到什么异常才重试（这里是所有 Exception）
     * - maxAttempts: 最多尝试几次（包括第一次，所以是"1次正常 + 2次重试"）
     * - backoff.delay: 第一次重试前等待多少毫秒（2秒）
     * - backoff.multiplier: 每次重试等待时间乘以多少（指数退避：2秒→4秒→8秒）
     *
     * 对应 Celery 的：
     *   retry_backoff=True, retry_backoff_max=10, max_retries=3
     */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void generateWithRetry(Long carePlanId) {
        // 找到 CarePlan
        CarePlan carePlan = carePlanRepository.findById(carePlanId)
                .orElseThrow(() -> new RuntimeException("CarePlan not found: " + carePlanId));

        Order order = carePlan.getOrder();

        // 拼病人信息
        String patientInfo = buildPatientInfo(order);

        // 调用 LLM（这里可能失败，失败了 @Retryable 会自动重试）
        log.info("🤖 调用 LLM 生成 Care Plan... (carePlanId={})", carePlanId);
        String content = llmService.generateCarePlan(patientInfo);

        // 成功：更新数据库
        carePlan.setContent(content);
        carePlan.setStatus(CarePlan.Status.COMPLETED);
        carePlanRepository.save(carePlan);

        log.info("✅ Care Plan 生成完成 (carePlanId={})", carePlanId);
    }

    /**
     * @Recover：当重试全部用完还是失败时，会调用这个方法
     *
     * 注意：方法签名第一个参数必须是异常类型，后面参数和 @Retryable 方法一致
     *
     * 对应 Celery 的：
     *   @app.task(on_failure=handle_failure)
     */
    @Recover
    public void handleAllRetriesExhausted(Exception e, Long carePlanId) {
        log.error("❌ 重试 3 次全部失败，标记为 FAILED (carePlanId={})", carePlanId);
        log.error("   失败原因: {}", e.getMessage());

        // 把状态改成 FAILED，用户可以后续手动重新提交
        carePlanRepository.findById(carePlanId).ifPresent(carePlan -> {
            carePlan.setStatus(CarePlan.Status.FAILED);
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
                order.getAdditionalDiagnosis() != null ? order.getAdditionalDiagnosis() : "N/A",
                order.getMedicationHistory() != null ? order.getMedicationHistory() : "N/A",
                order.getPatientRecords() != null ? order.getPatientRecords() : "N/A"
        );
    }
}

