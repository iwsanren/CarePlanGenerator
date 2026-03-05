package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.repository.CarePlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Care Plan Worker - 手写版本（Day 5 学习用）
 *
 * 这个 Worker 做三件事：
 * 1. 每隔 5 秒从 Redis 队列里拉一个任务
 * 2. 调用 LLM 生成 Care Plan
 * 3. 把结果存回数据库
 *
 * 你会发现的问题（这是故意的！）：
 * - 前端不知道完成了 → Day 6 再解决
 * - 每次只处理一个，积压了怎么办？
 * - LLM 失败了怎么办？只是 log 了一下，没有重试
 * - Worker 自己崩了怎么办？正在处理的任务丢了
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CarePlanWorker {

    private final QueueService queueService;
    private final LLMService llmService;
    private final CarePlanRepository carePlanRepository;

    /**
     * 每 5 秒执行一次，从队列里拉一个任务来处理
     *
     * fixedDelay = 5000 意思是：
     * 上一次处理完毕后，等 5 秒再执行下一次
     * （不是每 5 秒固定触发，而是处理完再等 5 秒）
     */
    @Scheduled(fixedDelay = 5000)
    public void processNextTask() {

        // 步骤 1：从 Redis 队列里拉一个任务
        Long carePlanId = queueService.dequeue();

        // 队列为空，没有任务，什么都不做，等下一次
        if (carePlanId == null) {
            return;  // 安静地退出，不打 log（否则每 5 秒刷一条"没任务"很烦）
        }

        log.info("🔄 Worker 拿到任务: carePlanId={}", carePlanId);

        // 步骤 2：从数据库找到这个 CarePlan
        CarePlan carePlan = carePlanRepository.findById(carePlanId).orElse(null);

        if (carePlan == null) {
            log.warn("⚠️  找不到 CarePlan，跳过: carePlanId={}", carePlanId);
            return;
        }

        // 步骤 3：把状态改成 PROCESSING，表示"正在处理"
        carePlan.setStatus(CarePlan.Status.PROCESSING);
        carePlanRepository.save(carePlan);
        log.info("⚙️  状态改为 PROCESSING: carePlanId={}", carePlanId);

        // 步骤 4：调用 LLM 生成 Care Plan
        try {
            // 拼出病人信息给 LLM
            Order order = carePlan.getOrder();
            String patientInfo = buildPatientInfo(order);

            log.info("🤖 开始调用 LLM 生成 Care Plan...");
            String content = llmService.generateCarePlan(patientInfo);

            // 步骤 5：把结果存回数据库，状态改成 COMPLETED
            carePlan.setContent(content);
            carePlan.setStatus(CarePlan.Status.COMPLETED);
            carePlanRepository.save(carePlan);

            log.info("✅ Care Plan 生成完成: carePlanId={}", carePlanId);

            // ⚠️ 注意：到这里就结束了！
            // Worker 不会通知前端。前端还在显示 "PENDING"。
            // 用户必须手动刷新才能看到结果。
            // 这就是今天故意留下的"痛点"，明天用 Polling 解决。

        } catch (Exception e) {
            // LLM 调用失败了，把状态改成 FAILED
            log.error("❌ Care Plan 生成失败: carePlanId={}, 原因: {}", carePlanId, e.getMessage());

            carePlan.setStatus(CarePlan.Status.FAILED);
            carePlanRepository.save(carePlan);

            // 问题来了：失败了怎么办？
            // 现在的处理方式：只打一行 log，然后就放弃了
            // 真实场景应该重试，但重试逻辑写起来很烦
            // → Day 5 (Celery) 会看到框架自动帮你重试
        }
    }

    /**
     * 把订单信息拼成一段文字，发给 LLM
     */
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



