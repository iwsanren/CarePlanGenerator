package com.page24.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Small, bounded pool for the event-driven CarePlan worker trigger.
     * Kept intentionally small: LLM generation is the bottleneck, not thread
     * count. Spring's default async executor (SimpleAsyncTaskExecutor) spawns
     * one thread per task with no cap, which would let a burst of orders
     * spawn unbounded threads — this pool caps that at 2 concurrent workers.
     */
    @Bean(name = "carePlanTaskExecutor")
    public Executor carePlanTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("careplan-worker-");
        executor.initialize();
        return executor;
    }
}
