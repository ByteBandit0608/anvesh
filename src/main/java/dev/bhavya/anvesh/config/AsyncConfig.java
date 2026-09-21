package dev.bhavya.anvesh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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
        // WHY CallerRunsPolicy: when the 500-slot queue is full the default policy throws
        // RejectedExecutionException — and because indexAsync() is fire-and-forget the exception is lost
        // and the document sits PENDING forever. CallerRunsPolicy instead runs the task on the HTTP
        // thread, which slows that one request down and thereby applies back-pressure to the client.
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let the JVM exit / the app restart cleanly instead of abandoning half-indexed docs.
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }
}
