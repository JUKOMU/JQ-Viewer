package io.github.jukomu.runtime;

import android.content.Context;
import android.net.*;
import androidx.annotation.NonNull;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;
import io.github.jukomu.platform.persistence.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程范围内允许为null的JMComic客户端生命周期管理及网络重试策略。
 */
public final class JmcomicSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(JmcomicSessionManager.class);

    public interface Listener {
        void onClientStateChanged(ClientStateSnapshot snapshot, JmApiClient client);

        void onNetworkEvent(NetworkEvent event);
    }

    public record ClientStateSnapshot(String state, String reason, long timestamp) {
    }

    public record NetworkEvent(String phase, String message, long timestamp,
                               Map<String, Integer> domains,
                               boolean allDeadFallback) {
    }

    private record NetworkEnvironment(boolean available, String fingerprint) {
    }

    private static final long NETWORK_DEBOUNCE_MS = 2000;
    private static JmcomicSessionManager instance;

    private final ConnectivityManager connectivityManager;
    private final SettingsStore settingsStore;
    private final ScheduledExecutorService executor =
        ServiceExecutors.scheduled("jmcomic-session", 1);
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicLong manualRetrySequence = new AtomicLong();
    private final AtomicLong probeSequence = new AtomicLong();
    private final AtomicBoolean networkRecoveryPending = new AtomicBoolean();
    private final ClientSession<JmApiClient> session;
    private final Object networkLock = new Object();
    private final Object stateDispatchLock = new Object();

    private ConnectivityManager.NetworkCallback networkCallback;
    private ScheduledFuture<?> pendingNetworkEvaluation;
    private ClientStateSnapshot latestClientState;
    private JmApiClient latestClient;
    private String lastObservedEnvironment;
    private long networkEnvironmentSequence;

    private JmcomicSessionManager(Context context, int downloadConcurrency) {
        connectivityManager = (ConnectivityManager) context.getApplicationContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE);
        settingsStore = SettingsStore.getInstance(context);
        session = new ClientSession<>(
            () -> JmComic.newApiClientAsync(new JmConfiguration.Builder()
                .downloadThreadPoolSize(downloadConcurrency)
                .build()).thenApply(this::applySavedRoute),
            new ClientSession.Observer<>() {
                @Override
                public void onStateChanged(ClientSession.Snapshot snapshot, JmApiClient client) {
                    LOGGER.info("JMComic client state state={} reason={} hasClient={}",
                        snapshot.state(), snapshot.reason(), client != null);
                    publishClientState(new ClientStateSnapshot(
                            snapshot.state(), snapshot.reason(), snapshot.timestamp()),
                        client);
                }

                @Override
                public void onReady(JmApiClient client) {
                    LOGGER.info("JMComic 客户端初始化完成，开始恢复后的线路探测");
                    scheduleDomainProbe(client, "客户端初始化完成");
                }

                @Override
                public void onFailure(Throwable error) {
                    LOGGER.warn("JMComic 客户端初始化失败，保持离线能力 errorType={}",
                        errorType(error));
                }

                @Override
                public void onDiscarded(JmApiClient client) {
                    LOGGER.info("JMComic 客户端已丢弃");
                    client.close();
                }
            }, executor);
        ClientSession.Snapshot initialSnapshot = session.getSnapshot();
        latestClientState = new ClientStateSnapshot(
            initialSnapshot.state(), initialSnapshot.reason(), initialSnapshot.timestamp());
        registerNetworkCallback();
        scheduleNetworkEvaluation();
    }

    private JmApiClient applySavedRoute(JmApiClient client) {
        if (!"manual".equals(settingsStore.getString("api_route_mode"))) return client;
        String domain = settingsStore.getString("api_route_domain");
        try {
            if (domain == null || domain.isBlank()) {
                throw new IllegalStateException("保存的手动线路为空");
            }
            client.useDomain(domain);
            LOGGER.info("应用保存的手动线路 routeHash={}", stableHash(domain));
            return client;
        } catch (RuntimeException error) {
            LOGGER.warn("应用保存的手动线路失败 routeHash={} errorType={}",
                stableHash(domain), error.getClass().getSimpleName());
            client.close();
            throw error;
        }
    }

    public static synchronized JmcomicSessionManager getOrCreate(
        Context context, int downloadConcurrency) {
        if (instance == null) {
            instance = new JmcomicSessionManager(context, downloadConcurrency);
        }
        return instance;
    }

    public JmApiClient getClient() {
        return session.getClient();
    }

    public ClientStateSnapshot getClientState() {
        synchronized (stateDispatchLock) {
            return latestClientState;
        }
    }

    public void attachListener(Listener listener) {
        synchronized (stateDispatchLock) {
            listeners.add(listener);
            listener.onClientStateChanged(latestClientState, latestClient);
        }
    }

    public void detachListener(Listener listener) {
        synchronized (stateDispatchLock) {
            listeners.remove(listener);
        }
    }

    public void retryOrReprobe() {
        JmApiClient client = session.getClient();
        if (client != null) {
            long retryId = manualRetrySequence.incrementAndGet();
            LOGGER.info("收到手动域名探活请求 retryId={} clientReady=true", retryId);
            scheduleDomainProbe(client, "手动请求");
            return;
        }
        NetworkEnvironment environment = currentEnvironment();
        long retryId = manualRetrySequence.incrementAndGet();
        LOGGER.info("收到手动客户端恢复请求 retryId={} networkAvailable={} environmentHash={}",
            retryId, environment.available(), stableHash(environment.fingerprint()));
        if (!environment.available()) {
            session.updateEnvironment(environment.fingerprint(), false);
            return;
        }
        session.updateEnvironment(
            environment.fingerprint() + "|manual:" + manualRetrySequence.incrementAndGet(),
            true);
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) {
            LOGGER.warn("ConnectivityManager 不可用，跳过网络监听");
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                LOGGER.info("网络可用事件 networkHash={}", stableHash(network.toString()));
                scheduleNetworkEvaluation();
            }

            @Override
            public void onLost(@NonNull Network network) {
                LOGGER.info("网络丢失事件 networkHash={}", stableHash(network.toString()));
                scheduleNetworkEvaluation();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                                              @NonNull NetworkCapabilities capabilities) {
                LOGGER.info("网络能力变化事件 networkHash={}", stableHash(network.toString()));
                scheduleNetworkEvaluation();
            }

            @Override
            public void onLinkPropertiesChanged(@NonNull Network network,
                                                @NonNull LinkProperties linkProperties) {
                LOGGER.info("网络链路变化事件 networkHash={}", stableHash(network.toString()));
                scheduleNetworkEvaluation();
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException error) {
            LOGGER.warn("注册网络变化监听失败 errorType={}", errorType(error));
        }
    }

    private void scheduleNetworkEvaluation() {
        synchronized (networkLock) {
            if (pendingNetworkEvaluation != null) {
                pendingNetworkEvaluation.cancel(false);
            }
            pendingNetworkEvaluation = executor.schedule(
                this::evaluateCurrentNetwork,
                NETWORK_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS);
        }
    }

    private void evaluateCurrentNetwork() {
        NetworkEnvironment environment = currentEnvironment();
        String previous;
        long environmentSequence;
        synchronized (networkLock) {
            pendingNetworkEvaluation = null;
            previous = lastObservedEnvironment;
            if (!environment.fingerprint().equals(previous)) {
                lastObservedEnvironment = environment.fingerprint();
                networkEnvironmentSequence++;
            }
            environmentSequence = networkEnvironmentSequence;
        }

        if (!environment.available()) {
            if (!environment.fingerprint().equals(previous)) {
                LOGGER.info("网络评估结果不可用 environmentHash={}",
                    stableHash(environment.fingerprint()));
                publishNetworkEvent(new NetworkEvent(
                    "network_lost", "网络不可用，在线客户端保持未创建状态",
                    System.currentTimeMillis(), null, false));
            }
            session.updateEnvironment(environment.fingerprint(), false);
            return;
        }

        boolean changed = !environment.fingerprint().equals(previous);
        if (changed) {
            LOGGER.info("网络环境发生变化，旧摘要={}，新摘要={}", fingerprintSummary(previous),
                fingerprintSummary(environment.fingerprint()));
            if (previous != null) networkRecoveryPending.set(true);
        }
        if (changed && previous != null) {
            publishNetworkEvent(new NetworkEvent(
                "network_changed", "网络环境已变化",
                System.currentTimeMillis(), null, false));
        }

        JmApiClient client = session.getClient();
        if (changed || client == null) {
            LOGGER.info("{}触发客户端恢复 environmentHash={} environmentSequence={}",
                changed ? "网络环境变化" : "客户端未初始化",
                stableHash(environment.fingerprint()), environmentSequence);
            session.updateEnvironment(
                environment.fingerprint() + "|epoch:" + environmentSequence,
                true);
        }
    }

    private NetworkEnvironment currentEnvironment() {
        if (connectivityManager == null) {
            return new NetworkEnvironment(false, "no_connectivity_manager");
        }
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return new NetworkEnvironment(false, "no_network");
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        boolean available = capabilities != null
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
        return new NetworkEnvironment(
            available,
            buildFingerprint(network, capabilities, linkProperties));
    }

    private static String buildFingerprint(Network network,
                                           NetworkCapabilities capabilities,
                                           LinkProperties linkProperties) {
        StringBuilder result = new StringBuilder(network.toString());
        if (capabilities != null) {
            result.append("|validated=")
                .append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            for (int transport = 0; transport <= 7; transport++) {
                if (capabilities.hasTransport(transport)) {
                    result.append("|transport=").append(transport);
                }
            }
        }
        if (linkProperties != null) {
            result.append("|proxy=").append(proxyFingerprint(linkProperties.getHttpProxy()));
        }
        return result.toString();
    }

    static String proxyFingerprint(ProxyInfo proxy) {
        if (proxy == null) return "direct";
        if (proxy.getPacFileUrl() != null && !Uri.EMPTY.equals(proxy.getPacFileUrl())) {
            return "pac:" + proxy.getPacFileUrl();
        }
        return "http:" + proxy.getHost() + ":" + proxy.getPort();
    }

    private void scheduleDomainProbe(JmApiClient client, String trigger) {
        long probeId = probeSequence.incrementAndGet();
        LOGGER.info("域名探活已调度 probeId={} trigger={}", probeId, trigger);
        executor.execute(() -> {
            if (session.getClient() == client) {
                probeDomains(client, probeId, trigger);
            } else {
                LOGGER.info("域名探活已跳过 probeId={} reason=client_replaced", probeId);
            }
        });
    }

    private void probeDomains(JmApiClient client, long probeId, String trigger) {
        long startedAt = System.nanoTime();
        LOGGER.info("开始域名探活 probeId={} trigger={}", probeId, trigger);
        publishNetworkEvent(new NetworkEvent(
            "probing", "正在探测域名连通性...",
            System.currentTimeMillis(), null, false));
        try {
            client.reprobeDomains();
            Map<String, Integer> states = new LinkedHashMap<>(client.getDomainStates());
            boolean allDeadFallback = !states.isEmpty()
                && states.values().stream().allMatch(value -> value != null && value == -1);
            int alive = (int) states.values().stream()
                .filter(JmcomicSessionManager::isDomainReachable)
                .count();
            publishNetworkEvent(new NetworkEvent(
                "result",
                allDeadFallback
                    ? "探活完成 · 全部不可达"
                    : "探活完成 · " + alive + "/" + states.size() + " 可达",
                System.currentTimeMillis(), states, allDeadFallback));
            LOGGER.info("域名探活完成 probeId={} alive={} total={} elapsedMs={}",
                probeId, alive, states.size(), elapsedMillis(startedAt));
            if (networkRecoveryPending.compareAndSet(true, false)) {
                publishNetworkEvent(new NetworkEvent(
                    "network_restored", "网络恢复并完成线路探测",
                    System.currentTimeMillis(), states, allDeadFallback));
            }
        } catch (RuntimeException error) {
            LOGGER.warn("域名重新探活失败 probeId={} elapsedMs={} errorType={}",
                probeId, elapsedMillis(startedAt), error.getClass().getSimpleName());
            String message = error.getMessage();
            publishNetworkEvent(new NetworkEvent(
                "error", "探活异常" + (message == null ? "" : " · " + message),
                System.currentTimeMillis(), null, false));
        }
    }

    private void publishClientState(ClientStateSnapshot snapshot, JmApiClient client) {
        synchronized (stateDispatchLock) {
            latestClientState = snapshot;
            latestClient = client;
            for (Listener listener : listeners) {
                listener.onClientStateChanged(snapshot, client);
            }
        }
    }

    private void publishNetworkEvent(NetworkEvent event) {
        for (Listener listener : listeners) {
            listener.onNetworkEvent(event);
        }
    }

    private static boolean isDomainReachable(Integer state) {
        return state != null && state >= 0 && state < (Integer.MAX_VALUE / 2);
    }

    private static String fingerprintSummary(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return "空";
        return Integer.toHexString(fingerprint.hashCode());
    }

    private static String stableHash(String value) {
        if (value == null || value.isEmpty()) return "empty";
        return Integer.toHexString(value.hashCode());
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
