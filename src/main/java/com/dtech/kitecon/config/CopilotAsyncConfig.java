package com.dtech.kitecon.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async thread pool for Co-Pilot investigation runs.
 * Investigations run in the background so the UI doesn't block.
 * Future: results pushed via WebSocket after completion.
 */
@Configuration
@EnableAsync
public class CopilotAsyncConfig {

    @Value("${copilot.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${copilot.async.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${copilot.async.queue-capacity:50}")
    private int queueCapacity;

    @Bean(name = "copilotExecutor")
    public Executor copilotExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("copilot-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
