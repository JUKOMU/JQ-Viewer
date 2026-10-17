package io.github.jukomu.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android 各服务使用的命名无界执行器工厂。
 */
public final class ServiceExecutors {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceExecutors.class);

    private ServiceExecutors() {
    }

    public static ThreadPoolExecutor fixed(String serviceName, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            namedFactory(serviceName),
            rejectedHandler(serviceName));
        LOGGER.info("service_executors event=created pool={} type=fixed threads={} status=ready",
            serviceName, threads);
        return executor;
    }

    public static ScheduledThreadPoolExecutor scheduled(String serviceName, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            threads,
            namedFactory(serviceName),
            rejectedHandler(serviceName));
        executor.setRemoveOnCancelPolicy(true);
        LOGGER.info("service_executors event=created pool={} type=scheduled threads={} status=ready",
            serviceName, threads);
        return executor;
    }

    private static RejectedExecutionHandler rejectedHandler(String serviceName) {
        return (runnable, executor) -> {
            LOGGER.warn("service_executors event=task_rejected pool={} shutdown={} "
                    + "queueSize={} status=failed", serviceName, executor.isShutdown(),
                executor.getQueue().size());
            throw new RejectedExecutionException("线程池任务已拒绝: " + serviceName);
        };
    }

    private static ThreadFactory namedFactory(String serviceName) {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("serviceName is required");
        }
        String prefix = "jq-viewer-" + serviceName.trim() + "-";
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + sequence.incrementAndGet());
    }
}
