package io.github.jukomu.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.util.Log;
import androidx.annotation.NonNull;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide nullable JMComic client lifecycle and network retry policy.
 */
public final class JmcomicSessionManager {

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

    private static final String TAG = "JmcomicSession";
    private static final long NETWORK_DEBOUNCE_MS = 2000;
    private static JmcomicSessionManager instance;

    private final ConnectivityManager connectivityManager;
    private final ScheduledExecutorService executor =
        ServiceExecutors.scheduled("jmcomic-session", 1);
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicLong manualRetrySequence = new AtomicLong();
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
        session = new ClientSession<>(
            () -> JmComic.newApiClientAsync(new JmConfiguration.Builder()
                .downloadThreadPoolSize(downloadConcurrency)
                .build()),
            new ClientSession.Observer<>() {
                @Override
                public void onStateChanged(ClientSession.Snapshot snapshot, JmApiClient client) {
                    publishClientState(new ClientStateSnapshot(
                        snapshot.state(), snapshot.reason(), snapshot.timestamp()),
                        client);
                }

                @Override
                public void onReady(JmApiClient client) {
                    Log.i(TAG, "JMComic 客户端初始化完成");
                    scheduleDomainProbe(client);
                }

                @Override
                public void onFailure(Throwable error) {
                    Log.w(TAG, "JMComic 客户端初始化失败，保持离线能力", error);
                }

                @Override
                public void onDiscarded(JmApiClient client) {
                    client.close();
                }
            });
        ClientSession.Snapshot initialSnapshot = session.getSnapshot();
        latestClientState = new ClientStateSnapshot(
            initialSnapshot.state(), initialSnapshot.reason(), initialSnapshot.timestamp());
        registerNetworkCallback();
        scheduleNetworkEvaluation();
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
            scheduleDomainProbe(client);
            return;
        }
        NetworkEnvironment environment = currentEnvironment();
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
            Log.w(TAG, "ConnectivityManager 不可用，跳过网络监听");
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                scheduleNetworkEvaluation();
            }

            @Override
            public void onLost(@NonNull Network network) {
                scheduleNetworkEvaluation();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                                              @NonNull NetworkCapabilities capabilities) {
                scheduleNetworkEvaluation();
            }

            @Override
            public void onLinkPropertiesChanged(@NonNull Network network,
                                                @NonNull LinkProperties linkProperties) {
                scheduleNetworkEvaluation();
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException error) {
            Log.w(TAG, "注册网络变化监听失败", error);
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
                publishNetworkEvent(new NetworkEvent(
                    "network_lost", "网络不可用，在线客户端保持未创建状态",
                    System.currentTimeMillis(), null, false));
            }
            session.updateEnvironment(environment.fingerprint(), false);
            return;
        }

        boolean changed = !environment.fingerprint().equals(previous);
        if (changed) {
            publishNetworkEvent(new NetworkEvent(
                "network_changed", "网络环境已变化",
                System.currentTimeMillis(), null, false));
        }

        JmApiClient client = session.getClient();
        if (changed || client == null) {
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
            result.append("|interface=").append(linkProperties.getInterfaceName());
            ProxyInfo proxy = linkProperties.getHttpProxy();
            if (proxy != null) {
                result.append("|proxy=").append(proxy);
            }
        }
        return result.toString();
    }

    private void scheduleDomainProbe(JmApiClient client) {
        executor.execute(() -> {
            if (session.getClient() == client) probeDomains(client);
        });
    }

    private void probeDomains(JmApiClient client) {
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
        } catch (RuntimeException error) {
            Log.w(TAG, "域名重新探活失败", error);
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
}
