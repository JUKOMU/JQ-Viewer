package io.github.jukomu.runtime;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Android 各服务使用的命名无界执行器工厂。 */
public final class ServiceExecutors {

    private ServiceExecutors() {
    }

    public static ThreadPoolExecutor fixed(String serviceName, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }
        return new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            namedFactory(serviceName),
            new ThreadPoolExecutor.AbortPolicy());
    }

    public static ScheduledThreadPoolExecutor scheduled(String serviceName, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            threads,
            namedFactory(serviceName),
            new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
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
