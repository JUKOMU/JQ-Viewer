package io.github.jukomu.desktop.feature.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.client.JmDownloadClient;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.*;

/**
 * 管理 Desktop 进程内可空的 JMComic 在线客户端。
 */
public final class JmcomicSessionManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(JmcomicSessionManager.class);
    private static final String CLIENT_UNAVAILABLE = "在线客户端不可用";
    private static final long[] RETRY_DELAYS_SECONDS = {1, 2, 4, 8, 16, 30};

    @FunctionalInterface
    public interface Factory {
        CompletableFuture<? extends JmClient> create();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClientStateSnapshot(String state, String reason, long timestamp) {
    }

    private final Factory factory;
    private final boolean ownsClient;
    private final EventHub events;
    private final ScheduledExecutorService retryExecutor;

    private JmClient client;
    private CompletableFuture<? extends JmClient> pending;
    private ClientStateSnapshot snapshot;
    private boolean closed;
    private int retryIndex;
    private ScheduledFuture<?> retryTask;
    private long retryGeneration;
    private long pendingStartedNanos;
    private long attemptSequence;
    private long pendingAttemptSequence;

    private JmcomicSessionManager(
        Factory factory,
        JmClient client,
        boolean ownsClient,
        EventHub events
    ) {
        this.factory = factory;
        this.client = client;
        this.ownsClient = ownsClient;
        this.events = Objects.requireNonNull(events, "events");
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "jq-viewer-client-retry");
            thread.setDaemon(true);
            return thread;
        });
        this.snapshot = client == null
            ? snapshot("unavailable", "no_network")
            : snapshot("ready", null);
    }

    public static JmcomicSessionManager managed(Factory factory, EventHub events) {
        return new JmcomicSessionManager(
            Objects.requireNonNull(factory, "factory"), null, true, events);
    }

    public static JmcomicSessionManager provided(JmClient client, EventHub events) {
        Objects.requireNonNull(client, "client");
        if (!(client instanceof JmDownloadClient)) {
            throw new IllegalArgumentException("JMComic 客户端不支持下载任务控制");
        }
        return new JmcomicSessionManager(null, client, false, events);
    }

    public void startOrRetry() {
        CompletableFuture<? extends JmClient> attempt;
        ClientStateSnapshot changed;
        synchronized (this) {
            if (closed || client != null || pending != null || factory == null) return;
            if (retryTask != null) {
                retryTask.cancel(false);
                retryGeneration++;
                retryTask = null;
            }
            changed = setSnapshot("initializing", null);
            pendingStartedNanos = System.nanoTime();
            pendingAttemptSequence = ++attemptSequence;
            LOGGER.info("client_init operation=start attempt={} retryIndex={}",
                pendingAttemptSequence, retryIndex);
            try {
                attempt = factory.create();
                if (attempt == null) {
                    attempt = CompletableFuture.failedFuture(
                        new IllegalStateException("JMComic 客户端工厂返回了空 Future"));
                }
            } catch (Throwable failure) {
                attempt = CompletableFuture.failedFuture(failure);
            }
            pending = attempt;
            publish(changed);
        }
        attempt.whenComplete(this::completeAttempt);
    }

    public synchronized JmClient getClient() {
        return client;
    }

    public synchronized ClientStateSnapshot getSnapshot() {
        return snapshot;
    }

    public JmClient requireClient() {
        JmClient current = getClient();
        if (current == null) throw ApiException.unavailable(CLIENT_UNAVAILABLE);
        return current;
    }

    public JmDownloadClient requireDownloadClient() {
        JmClient current = requireClient();
        if (!(current instanceof JmDownloadClient downloadClient)) {
            throw ApiException.unavailable("在线客户端不支持下载任务控制");
        }
        return downloadClient;
    }

    private void completeAttempt(JmClient value, Throwable failure) {
        long elapsed;
        long attempt;
        ClientStateSnapshot changed;
        boolean closeValue = false;
        boolean ready = false;
        boolean reportFailure = false;
        synchronized (this) {
            elapsed = elapsedMs(pendingStartedNanos);
            attempt = pendingAttemptSequence;
            pending = null;
            if (closed) {
                closeValue = ownsClient && value != null;
                changed = null;
            } else if (failure == null && value instanceof JmDownloadClient) {
                client = value;
                retryIndex = 0;
                changed = setSnapshot("ready", null);
                ready = true;
            } else {
                changed = setSnapshot("unavailable", "initialization_failed");
                reportFailure = true;
                scheduleRetryLocked();
            }
            publish(changed);
        }

        if (closeValue) closeClient(value);
        if (ready) {
            LOGGER.info("client_init operation=complete status=success attempt={} elapsedMs={}",
                attempt, elapsed);
        } else if (reportFailure) {
            Throwable cause = failure != null
                ? unwrap(failure)
                : new IllegalStateException("JMComic 客户端不支持下载任务控制");
            LOGGER.warn("client_init operation=complete status=failed attempt={} errorClass={} elapsedMs={}",
                attempt, cause.getClass().getSimpleName(), elapsed);
            if (ownsClient && value != null) closeClient(value);
        }
    }

    private void scheduleRetryLocked() {
        if (closed || client != null || pending != null || retryTask != null) return;
        long delay = RETRY_DELAYS_SECONDS[Math.min(retryIndex, RETRY_DELAYS_SECONDS.length - 1)];
        LOGGER.info("client_init operation=retry_scheduled retryIndex={} delaySeconds={}", retryIndex, delay);
        retryIndex++;
        long expectedRetryGeneration = ++retryGeneration;
        retryTask = retryExecutor.schedule(() -> {
            synchronized (this) {
                if (expectedRetryGeneration != retryGeneration || retryTask == null) return;
                retryTask = null;
                if (closed || client != null || pending != null) return;
                startOrRetry();
            }
        }, delay, TimeUnit.SECONDS);
    }

    private synchronized ClientStateSnapshot setSnapshot(String state, String reason) {
        if (state.equals(snapshot.state()) && Objects.equals(reason, snapshot.reason())) {
            return null;
        }
        long timestamp = Math.max(System.currentTimeMillis(), snapshot.timestamp() + 1);
        snapshot = new ClientStateSnapshot(state, reason, timestamp);
        return snapshot;
    }

    private void publish(ClientStateSnapshot changed) {
        if (changed != null) events.publishRetained("clientStateChanged", changed);
    }

    @Override
    public void close() {
        CompletableFuture<? extends JmClient> pendingAttempt;
        JmClient current;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pendingAttempt = pending;
            pending = null;
            if (retryTask != null) retryTask.cancel(false);
            retryGeneration++;
            retryTask = null;
            current = client;
            client = null;
        }
        if (pendingAttempt != null) pendingAttempt.cancel(true);
        retryExecutor.shutdownNow();
        if (ownsClient && current != null) closeClient(current);
    }

    private static ClientStateSnapshot snapshot(String state, String reason) {
        return new ClientStateSnapshot(state, reason, System.currentTimeMillis());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeClient(JmClient client) {
        try {
            if (client instanceof JmApiClient apiClient) apiClient.close();
            else if (client instanceof AutoCloseable closeable) closeable.close();
        } catch (Exception failure) {
            LOGGER.warn("client_init operation=close status=failed errorClass={}",
                failure.getClass().getSimpleName());
        }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
