package io.github.jukomu.bridge;

import io.github.jukomu.feature.catalog.ApiService;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 持有当前插件会话的 API 服务和执行线程。
 */
final class ApiSession {

    private static final int API_EXECUTOR_SIZE = 12;

    private final ExecutorService apiExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final ApiService apiService;

    ApiSession(JmApiClient client) {
        this(client, Executors.newFixedThreadPool(API_EXECUTOR_SIZE),
            Executors.newSingleThreadScheduledExecutor());
    }

    ApiSession(JmApiClient client, ExecutorService apiExecutor,
               ScheduledExecutorService timeoutExecutor) {
        this.apiExecutor = apiExecutor;
        this.timeoutExecutor = timeoutExecutor;
        this.apiService = new ApiService(client, apiExecutor, timeoutExecutor);
    }

    ApiService getApiService() {
        return apiService;
    }

    void destroy() {
        shutdownGracefully(timeoutExecutor, 2);
        shutdownGracefully(apiExecutor, 10);
    }

    private static void shutdownGracefully(ExecutorService executor,
                                           int timeoutSeconds) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException error) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
