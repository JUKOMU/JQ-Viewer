package io.github.jukomu.feature.update;

import java.net.HttpURLConnection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 到达总时限时断开更新请求；连接和空闲超时由 HttpURLConnection 处理。
 * 请求结束或取消时必须关闭定时任务。
 */
final class RequestDeadline implements AutoCloseable {
    private final ScheduledFuture<?> task;
    private volatile boolean expired;

    RequestDeadline(ScheduledExecutorService scheduler, HttpURLConnection connection, long timeoutMs) {
        task = scheduler.schedule(() -> {
            expired = true;
            connection.disconnect();
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean expired() {
        return expired;
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
