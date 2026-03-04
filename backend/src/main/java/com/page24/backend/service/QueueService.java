package com.page24.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 队列服务
 *
 * Day 4 学习要点：
 * 1. Redis 只是一个"存储空间"，用来存放待处理的任务
 * 2. 这个服务只负责"放进去"，不负责处理
 * 3. 处理任务的部分（Worker）是 Day 5 的内容
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueueService {

    private final RedisTemplate<String, Object> redisTemplate;

    // 队列的名字
    private static final String QUEUE_NAME = "careplan:queue";

    /**
     * 把 CarePlan ID 放进队列
     *
     * @param carePlanId CarePlan的ID
     */
    public void enqueue(Long carePlanId) {
        log.info("📥 放入队列: carePlanId={}", carePlanId);

        // 把 ID 放到 Redis 队列的右边（尾部）
        redisTemplate.opsForList().rightPush(QUEUE_NAME, carePlanId.toString());

        log.info("✅ 已放入队列，当前队列长度: {}", getQueueSize());
    }

    /**
     * 从队列取出一个任务（左边/头部）
     *
     * 注意：这个方法 Day 4 不会用，是 Day 5 Worker 才会用的
     *
     * @return CarePlan ID，如果队列为空则返回 null
     */
    public Long dequeue() {
        String carePlanId = (String) redisTemplate.opsForList().leftPop(QUEUE_NAME);
        if (carePlanId != null) {
            log.info("📤 从队列取出: carePlanId={}", carePlanId);
            return Long.parseLong(carePlanId);
        }
        return null;
    }

    /**
     * 查看队列长度
     */
    public Long getQueueSize() {
        Long size = redisTemplate.opsForList().size(QUEUE_NAME);
        return size != null ? size : 0L;
    }

    /**
     * 查看队列里所有的任务（不删除）
     */
    public java.util.List<Object> viewQueue() {
        Long size = getQueueSize();
        if (size == 0) {
            return java.util.Collections.emptyList();
        }
        return redisTemplate.opsForList().range(QUEUE_NAME, 0, -1);
    }
}

