package io.github.jukomu.desktop.feature.network;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.feature.network.model.*;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 读取 JMComic 域名状态、合并探活请求并监听 Desktop 网络变化。
 */
public final class NetworkService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkService.class);
    private static final long NETWORK_FINGERPRINT_STABILITY_NANOS =
        TimeUnit.SECONDS.toNanos(2);

    private final Operations operations;
    private final Executor executor;
    private final Consumer<NetworkProbeEvent> eventPublisher;
    private final ScheduledExecutorService networkMonitor;
    private final Object lifecycleLock = new Object();
    private boolean probing;
    private boolean closed;
    private boolean awaitingClient;
    private boolean pendingRecovery;
    private boolean pendingNetworkRecovery;
    private final FingerprintTracker fingerprintTracker;

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
        if (operations.recoverNetwork() == null) {
            fingerprintTracker = null;
            networkMonitor = null;
        } else {
            fingerprintTracker = new FingerprintTracker(currentNetworkFingerprint());
            networkMonitor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "jq-viewer-network-monitor");
                thread.setDaemon(true);
                return thread;
            });
            networkMonitor.scheduleWithFixedDelay(
                this::checkNetwork, 500, 1000, TimeUnit.MILLISECONDS);
        }
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
            if (probing) {
                pendingRecovery = true;
                LOGGER.info("收到手动域名探活请求，当前探活进行中，已排队下一轮");
                return;
            }
            probing = true;
            LOGGER.info("收到手动域名探活请求，开始探活");
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
            finishProbe();
        }
    }

    private void publish(NetworkProbeEvent event) {
        synchronized (lifecycleLock) {
            if (closed) return;
        }
        eventPublisher.accept(event);
    }

    private void checkNetwork() {
        String current = currentNetworkFingerprint();
        String previous = null;
        boolean changed;
        synchronized (lifecycleLock) {
            if (closed) return;
            FingerprintObservation observation = fingerprintTracker.observe(
                current, System.nanoTime());
            changed = observation.changed();
            if (changed) {
                previous = observation.previous();
            } else if (!observation.pending() && awaitingClient
                && operations.clientReady() != null
                && operations.clientReady().getAsBoolean()) {
                awaitingClient = false;
            } else {
                return;
            }
        }
        if (changed) {
            LOGGER.info("网络指纹发生变化，旧摘要={}，新摘要={}", fingerprintSummary(previous),
                fingerprintSummary(current));
        }
        if (!changed) {
            scheduleRecovery("客户端恢复就绪");
            return;
        }
        if (current.isEmpty()) {
            synchronized (lifecycleLock) {
                awaitingClient = false;
            }
            if (!previous.isEmpty()) publish(NetworkProbeEvent.networkLost());
            LOGGER.info("网络指纹为空，暂不执行网络恢复探活");
            return;
        }

        publish(NetworkProbeEvent.networkChanged());
        if (operations.clientReady() != null && !operations.clientReady().getAsBoolean()) {
            synchronized (lifecycleLock) {
                awaitingClient = true;
            }
            if (operations.retryClient() != null) operations.retryClient().run();
            return;
        }
        synchronized (lifecycleLock) {
            awaitingClient = false;
        }
        scheduleRecovery("网络指纹变化");
    }

    private void scheduleRecovery(String trigger) {
        synchronized (lifecycleLock) {
            if (closed) return;
            if (probing) {
                pendingRecovery = true;
                pendingNetworkRecovery = true;
                LOGGER.info("{}触发网络恢复探活，当前探活进行中，已排队下一轮", trigger);
                return;
            }
            probing = true;
            LOGGER.info("{}触发网络恢复探活，开始探活", trigger);
            try {
                executor.execute(this::recoverAndProbe);
            } catch (RejectedExecutionException exception) {
                probing = false;
                publish(NetworkProbeEvent.error("网络恢复任务无法启动，请稍后重试"));
            }
        }
    }

    private void recoverAndProbe() {
        synchronized (lifecycleLock) {
            if (closed) return;
        }
        try {
            operations.recoverNetwork().run();
            publish(NetworkProbeEvent.probing());
            operations.reprobe().run();
            publish(NetworkProbeEvent.networkRestored(
                toDomainStates(operations.domainStates().get())));
        } catch (RuntimeException exception) {
            publish(NetworkProbeEvent.error(messageOf("网络恢复探测失败", exception)));
        } finally {
            finishProbe();
        }
    }

    private void finishProbe() {
        boolean rejected = false;
        synchronized (lifecycleLock) {
            probing = false;
            if (closed || !pendingRecovery) return;
            boolean runNetworkRecovery = pendingNetworkRecovery;
            pendingRecovery = false;
            pendingNetworkRecovery = false;
            probing = true;
            try {
                executor.execute(runNetworkRecovery ? this::recoverAndProbe : this::runProbe);
            } catch (RejectedExecutionException exception) {
                probing = false;
                rejected = true;
            }
        }
        if (rejected) publish(NetworkProbeEvent.error("网络恢复任务无法启动，请稍后重试"));
    }

    private static String currentNetworkFingerprint() {
        try {
            String routeAddress;
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                // UDP connect only selects the operating-system route; it sends no packet.
                socket.connect(java.net.InetAddress.getByName("1.1.1.1"), 53);
                java.net.InetAddress localAddress = socket.getLocalAddress();
                if (localAddress.isAnyLocalAddress()) return "";
                routeAddress = localAddress.getHostAddress();
            }
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return routeAddress;
            List<String> active = new ArrayList<>();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                List<String> addresses = new ArrayList<>();
                var items = network.getInetAddresses();
                while (items.hasMoreElements()) {
                    addresses.add(items.nextElement().getHostAddress());
                }
                addresses.sort(String::compareTo);
                active.add(network.getName() + ":" + String.join(",", addresses));
            }
            active.sort(String::compareTo);
            return routeAddress + "|" + String.join("|", active);
        } catch (java.net.SocketException exception) {
            return "";
        } catch (java.io.IOException exception) {
            return "";
        }
    }

    private static String fingerprintSummary(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return "空";
        return Integer.toHexString(fingerprint.hashCode());
    }

    record FingerprintObservation(String previous, boolean changed, boolean pending) {
    }

    static final class FingerprintTracker {
        private String confirmed;
        private String candidate;
        private long candidateSinceNanos;

        FingerprintTracker(String initial) {
            confirmed = Objects.requireNonNull(initial, "initial");
        }

        FingerprintObservation observe(String current, long nowNanos) {
            Objects.requireNonNull(current, "current");
            if (current.equals(confirmed)) {
                candidate = null;
                return new FingerprintObservation(null, false, false);
            }
            if (!current.equals(candidate)) {
                candidate = current;
                candidateSinceNanos = nowNanos;
                return new FingerprintObservation(null, false, true);
            }
            if (nowNanos - candidateSinceNanos < NETWORK_FINGERPRINT_STABILITY_NANOS) {
                return new FingerprintObservation(null, false, true);
            }
            String previous = confirmed;
            confirmed = current;
            candidate = null;
            return new FingerprintObservation(previous, true, false);
        }
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
        if (networkMonitor != null) networkMonitor.shutdownNow();
    }

    /**
     * 把 JMComic 现有域名能力组合成 Desktop 可执行操作。
     */
    public record Operations(
        Supplier<Map<String, Integer>> domainStates,
        Supplier<Map<String, Integer>> latency,
        Runnable reprobe,
        Runnable recoverNetwork,
        BooleanSupplier clientReady,
        Runnable retryClient
    ) {
        public Operations(
            Supplier<Map<String, Integer>> domainStates,
            Supplier<Map<String, Integer>> latency,
            Runnable reprobe
        ) {
            this(domainStates, latency, reprobe, null, null, null);
        }

        public Operations(
            Supplier<Map<String, Integer>> domainStates,
            Supplier<Map<String, Integer>> latency,
            Runnable reprobe,
            Runnable recoverNetwork
        ) {
            this(domainStates, latency, reprobe, recoverNetwork, null, null);
        }

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
                client::reprobeDomains,
                client::recoverNetwork,
                () -> true,
                null);
        }
    }
}
