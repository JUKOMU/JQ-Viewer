package io.github.jukomu.desktop.feature.network;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.feature.network.model.*;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 读取 JMComic 域名状态，并串行合并重复的手动探活请求。
 */
public final class NetworkService implements AutoCloseable {
    private final Operations operations;
    private final Executor executor;
    private final Consumer<NetworkProbeEvent> eventPublisher;
    private final Object lifecycleLock = new Object();
    private boolean probing;
    private boolean closed;

    public NetworkService(Operations operations, Executor executor, EventHub eventHub) {
        this(operations, executor, event -> eventHub.publish("networkProbe", event));
    }

    NetworkService(
        Operations operations,
        Executor executor,
        Consumer<NetworkProbeEvent> eventPublisher
    ) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public DomainStatesResponse getDomainStates() {
        ensureOpen();
        try {
            return toDomainStates(operations.domainStates().get());
        } catch (RuntimeException exception) {
            if (exception instanceof ApiException apiException) throw apiException;
            throw ApiException.network(messageOf("获取域名状态失败，请稍后重试", exception));
        }
    }

    public LatencyResultsResponse measureLatency() {
        ensureOpen();
        final Map<String, Integer> latency;
        try {
            latency = operations.latency().get();
        } catch (RuntimeException exception) {
            if (exception instanceof ApiException apiException) throw apiException;
            throw ApiException.network(messageOf("测速失败，请稍后重试", exception));
        }

        List<LatencyResultResponse> results = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : safeMap(latency).entrySet()) {
            Integer measured = entry.getValue();
            boolean timedOut = measured == null || measured < 0;
            results.add(new LatencyResultResponse(
                entry.getKey(), timedOut ? 0 : measured, timedOut));
        }
        return new LatencyResultsResponse(List.copyOf(results));
    }

    public void reprobeDomains() {
        synchronized (lifecycleLock) {
            ensureOpenLocked();
            if (probing) return;
            probing = true;
            try {
                executor.execute(this::runProbe);
            } catch (RejectedExecutionException exception) {
                probing = false;
                throw ApiException.unavailable("当前请求过多，请稍后重试");
            }
        }
    }

    private void runProbe() {
        synchronized (lifecycleLock) {
            if (closed) {
                probing = false;
                return;
            }
        }
        try {
            publish(NetworkProbeEvent.probing());
            operations.reprobe().run();
            publish(NetworkProbeEvent.result(toDomainStates(operations.domainStates().get())));
        } catch (RuntimeException exception) {
            publish(NetworkProbeEvent.error(messageOf("探活异常", exception)));
        } finally {
            synchronized (lifecycleLock) {
                probing = false;
            }
        }
    }

    private void publish(NetworkProbeEvent event) {
        synchronized (lifecycleLock) {
            if (closed) return;
        }
        eventPublisher.accept(event);
    }

    private void ensureOpen() {
        synchronized (lifecycleLock) {
            ensureOpenLocked();
        }
    }

    private void ensureOpenLocked() {
        if (closed) throw ApiException.unavailable("网络服务已关闭");
    }

    private static DomainStatesResponse toDomainStates(Map<String, Integer> rawStates) {
        Map<String, Integer> states = safeMap(rawStates);
        boolean allDeadFallback = !states.isEmpty()
            && states.values().stream().allMatch(value -> value != null && value == -1);
        int alive = 0;
        List<DomainStateResponse> domains = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : states.entrySet()) {
            boolean reachable = isReachable(entry.getValue());
            if (reachable) alive++;
            domains.add(new DomainStateResponse(entry.getKey(), reachable));
        }
        return new DomainStatesResponse(
            List.copyOf(domains), alive, domains.size(), allDeadFallback);
    }

    private static boolean isReachable(Integer state) {
        return state != null && state >= 0 && state < (Integer.MAX_VALUE / 2);
    }

    private static Map<String, Integer> safeMap(Map<String, Integer> values) {
        return values == null ? Map.of() : values;
    }

    private static String messageOf(String fallback, RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : fallback + " · " + message;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
        }
    }

    /**
     * 把 JMComic 现有域名能力组合成 Desktop 可执行操作。
     */
    public record Operations(
        Supplier<Map<String, Integer>> domainStates,
        Supplier<Map<String, Integer>> latency,
        Runnable reprobe
    ) {
        public Operations {
            Objects.requireNonNull(domainStates, "domainStates");
            Objects.requireNonNull(latency, "latency");
            Objects.requireNonNull(reprobe, "reprobe");
        }

        public static Operations from(JmApiClient client) {
            Objects.requireNonNull(client, "client");
            return new Operations(
                client::getDomainStates,
                client::getDomainLatency,
                client::reprobeDomains);
        }
    }
}
