package com.gs.ais.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class FeishuAsyncConfig {

    @Bean("feishuObjectMapper")
    public ObjectMapper feishuObjectMapper() {
        return new ObjectMapper();
    }

    @Bean("feishuTaskExecutor")
    public TaskExecutor feishuTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("feishu-bot-");
        executor.initialize();
        return executor;
    }

    @Bean("operationLogTaskExecutor")
    public TaskExecutor operationLogTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("operation-log-");
        executor.initialize();
        return executor;
    }
}
