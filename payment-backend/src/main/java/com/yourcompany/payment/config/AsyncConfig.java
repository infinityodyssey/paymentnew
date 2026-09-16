package com.yourcompany.payment.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded platform-thread pool for interaction recording.
 *
 * Not virtual threads: on Java 21 a virtual thread that blocks inside a synchronized
 * block pins its carrier, and both Lettuce and BouncyCastle synchronize on paths this
 * service uses constantly. That turns a slow upstream into a stalled service rather
 * than a slow one.
 *
 * CallerRunsPolicy on saturation means the request thread performs the insert itself.
 * That slows one request instead of silently discarding an interaction record.
 *
 * setWaitForTasksToCompleteOnShutdown drains in-flight work inside the 25s graceful
 * shutdown window, so a rolling deploy does not drop rows. The audit ledger does not
 * depend on this: AuditService writes synchronously, because "probably written" is not
 * good enough for the regulatory record.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("applicationTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor(
            @Value("${app.async.core-pool-size:8}") int corePoolSize,
            @Value("${app.async.max-pool-size:32}") int maxPoolSize,
            @Value("${app.async.queue-capacity:500}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("app-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);

        // The correlation id is captured into the task's own MDC, because MDC is
        // thread-local and does not follow work across the pool boundary.
        executor.setTaskDecorator(runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        });

        executor.initialize();
        return executor;
    }
}
