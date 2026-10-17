package io.github.jukomu.bridge;

import io.github.jukomu.feature.catalog.ApiService;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.runtime.ServiceExecutors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 持有当前插件会话的 API 服务和执行线程。
 */
final class ApiSession {

    private static final int API_EXECUTOR_SIZE = 12;

    private final ExecutorService apiExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final ApiService apiService;
    private final PluginCallSession callSession = new PluginCallSession();

    ApiSession(JmApiClient client) {
        this(client, ServiceExecutors.fixed("api", API_EXECUTOR_SIZE),
            ServiceExecutors.scheduled("api-timeout", 1));
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

    PluginCallSession getCallSession() {
        return callSession;
    }

    void destroy() {
        callSession.close();
        timeoutExecutor.shutdownNow();
        apiExecutor.shutdownNow();
    }
}
