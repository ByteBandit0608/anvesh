package dev.bhavya.anvesh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated pool for ingestion so a burst of uploads can't starve request threads.
 * Embedding is CPU-bound, so we size it to the core count rather than oversubscribing.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "ingestExecutor")
    public Executor ingestExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(Math.max(2, cores / 2));
        ex.setMaxPoolSize(Math.max(2, cores));
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("ingest-");
        ex.initialize();
        return ex;
    }
}
