package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.repository.CarePlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Care Plan Worker - 从 Redis 队列拉任务
 *
 * 职责划分（对应 Celery 的结构）：
 *
 *   CarePlanWorker          ← 相当于 Celery Worker 进程（负责"拉任务"）
 *   CarePlanGenerationService ← 相当于 Celery Task 函数（负责"处理任务 + 重试"）
 *
 * Worker 只做两件事：
 * 1. 每 5 秒从 Redis 拉一个任务
 * 2. 把任务交给 CarePlanGenerationService 处理（它负责 LLM 调用和重试）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CarePlanWorker {

    private final QueueService queueService;
    private final CarePlanRepository carePlanRepository;
    private final CarePlanGenerationService carePlanGenerationService;

    /**
     * 每 5 秒执行一次，从队列里拉一个任务来处理
     *
     * fixedDelay = 5000：
     *   上一次处理完毕后等 5 秒，再执行下一次
     *   （不是每 5 秒固定触发，是处理完再等）
     */
    @Scheduled(fixedDelay = 5000)
    public void processNextTask() {

        // 步骤 1：从 Redis 拉任务，空队列就退出
        Long carePlanId = queueService.dequeue();
        if (carePlanId == null) {
            return;
        }

        log.info("🔄 Worker 拿到任务: carePlanId={}", carePlanId);

        // 步骤 2：状态改成 PROCESSING（告诉数据库"正在处理了"）
        carePlanRepository.findById(carePlanId).ifPresent(carePlan -> {
            carePlan.setStatus(CarePlan.Status.PROCESSING);
            carePlanRepository.save(carePlan);
        });
        log.info("⚙️  状态改为 PROCESSING: carePlanId={}", carePlanId);

        // 步骤 3：交给 GenerationService 处理
        // 它内部有 @Retryable，失败会自动重试，不用我们管
        carePlanGenerationService.generateWithRetry(carePlanId);

        // ⚠️ 处理完了，前端依然不知道！
        // 用户必须手动刷新才能看到 COMPLETED 状态。
        // 这就是今天故意留下的"痛点"——明天用 Polling 解决。
    }
}
