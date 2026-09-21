package io.github.jukomu.bridge;

import android.content.Context;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.jukomu.bridge.handler.ApiPluginHandler;
import io.github.jukomu.bridge.handler.AuthPluginHandler;
import io.github.jukomu.feature.catalog.ApiService;
import io.github.jukomu.feature.settings.SettingsService;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.platform.persistence.SettingsStore;
import io.github.jukomu.runtime.JmcomicSessionManager;

import java.util.Map;

/** Online JMComic bridge. Local application capabilities live in {@link JqViewerPlugin}. */
@CapacitorPlugin(name = "Jmcomic")
public class JmcomicPlugin extends Plugin {

    private static final String TAG = "JmcomicPlugin";
    private static final String CLIENT_UNAVAILABLE = "在线客户端不可用";

    private JmcomicSessionManager sessionManager;
    private JmcomicSessionManager.Listener sessionListener;
    private JmApiClient boundClient;
    private ApiSession apiSession;
    private ApiPluginHandler apiHandler;
    private AuthPluginHandler authHandler;

    @Override
    public void load() {
        Context context = getContext();
        SettingsStore settings = SettingsStore.getInstance(context);
        int downloadConcurrency = SettingsService.normalizeConcurrency(
            settings.getInt("download_concurrency", SettingsService.DEFAULT_CONCURRENCY));
        sessionManager = JmcomicSessionManager.getOrCreate(context, downloadConcurrency);
        sessionListener = new JmcomicSessionManager.Listener() {
            @Override
            public void onClientStateChanged(
                JmcomicSessionManager.ClientStateSnapshot snapshot) {
                if ("ready".equals(snapshot.state())) {
                    bindClient(sessionManager.getClient());
                }
                notifyListeners("clientStateChanged", clientStateJson(snapshot));
            }

            @Override
            public void onNetworkEvent(JmcomicSessionManager.NetworkEvent event) {
                notifyListeners("networkProbe", networkEventJson(event));
            }
        };
        sessionManager.attachListener(sessionListener);
        bindClient(sessionManager.getClient());
    }

    @Override
    protected void handleOnDestroy() {
        if (sessionManager != null && sessionListener != null) {
            sessionManager.detachListener(sessionListener);
        }
        synchronized (this) {
            if (apiSession != null) {
                apiSession.destroy();
                apiSession = null;
            }
            apiHandler = null;
            authHandler = null;
            boundClient = null;
        }
    }

    private synchronized void bindClient(JmApiClient client) {
        if (client == null || client == boundClient) {
            return;
        }
        if (apiSession != null) {
            apiSession.destroy();
        }
        apiSession = new ApiSession(client);
        ApiService apiService = apiSession.getApiService();
        PluginCallSession callSession = apiSession.getCallSession();
        apiHandler = new ApiPluginHandler(apiService, callSession);
        authHandler = new AuthPluginHandler(
            getContext(), apiService, client::getCookies, callSession);
        authHandler.clearAuthState(SettingsStore.getInstance(getContext()));
        boundClient = client;
    }

    @PluginMethod
    public void getClientState(PluginCall call) {
        call.resolve(clientStateJson(sessionManager.getClientState()));
    }

    /** Kept for older web bundles while getClientState is rolled out. */
    @PluginMethod
    public void getInitStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("complete", sessionManager.getClient() != null);
        call.resolve(result);
    }

    @PluginMethod
    public void getDomainStates(PluginCall call) {
        JmApiClient client = requireClient(call);
        if (client == null) return;
        try {
            call.resolve(domainStatesJson(client.getDomainStates()));
        } catch (RuntimeException error) {
            call.reject("获取域名状态失败，请稍后重试", error);
        }
    }

    @PluginMethod
    public void reprobeDomains(PluginCall call) {
        sessionManager.retryOrReprobe();
        call.resolve();
    }

    @PluginMethod
    public void measureLatency(PluginCall call) {
        JmApiClient client = requireClient(call);
        if (client == null) return;
        try {
            JSArray items = new JSArray();
            for (Map.Entry<String, Integer> entry : client.getDomainLatency().entrySet()) {
                JSObject item = new JSObject();
                item.put("domain", entry.getKey());
                int latency = entry.getValue();
                item.put("latencyMs", latency == -1 ? 0 : latency);
                item.put("timedOut", latency == -1);
                items.put(item);
            }
            JSObject result = new JSObject();
            result.put("results", items);
            call.resolve(result);
        } catch (RuntimeException error) {
            call.reject("测速失败，请稍后重试", error);
        }
    }

    @PluginMethod
    public void search(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.search(call);
    }

    @PluginMethod
    public void categories(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.categories(call);
    }

    @PluginMethod
    public void getAlbum(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.getAlbum(call);
    }

    @PluginMethod
    public void getPhoto(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.getPhoto(call);
    }

    @PluginMethod
    public void getComments(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.getComments(call);
    }

    @PluginMethod
    public void toggleAlbumLike(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.toggleAlbumLike(call);
    }

    @PluginMethod
    public void getFavorites(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.getFavorites(call);
    }

    @PluginMethod
    public void manageFavoriteFolder(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.manageFavoriteFolder(call);
    }

    @PluginMethod
    public void toggleAlbumFavorite(PluginCall call) {
        ApiPluginHandler handler = requireApiHandler(call);
        if (handler != null) handler.toggleAlbumFavorite(call);
    }

    @PluginMethod
    public void login(PluginCall call) {
        AuthPluginHandler handler = requireAuthHandler(call);
        if (handler != null) handler.login(call);
    }

    @PluginMethod
    public void logout(PluginCall call) {
        AuthPluginHandler handler = requireAuthHandler(call);
        if (handler != null) handler.logout(call);
    }

    @PluginMethod
    public void getUserProfile(PluginCall call) {
        AuthPluginHandler handler = requireAuthHandler(call);
        if (handler != null) handler.getUserProfile(call);
    }

    @PluginMethod
    public void checkLoginState(PluginCall call) {
        AuthPluginHandler handler = requireAuthHandler(call);
        if (handler != null) handler.checkLoginState(call);
    }

    @PluginMethod
    public void autoLogin(PluginCall call) {
        AuthPluginHandler handler = requireAuthHandler(call);
        if (handler != null) handler.autoLogin(call);
    }

    private synchronized ApiPluginHandler requireApiHandler(PluginCall call) {
        if (apiHandler == null) {
            call.reject(CLIENT_UNAVAILABLE);
            return null;
        }
        return apiHandler;
    }

    private synchronized AuthPluginHandler requireAuthHandler(PluginCall call) {
        if (authHandler == null) {
            call.reject(CLIENT_UNAVAILABLE);
            return null;
        }
        return authHandler;
    }

    private JmApiClient requireClient(PluginCall call) {
        JmApiClient client = sessionManager.getClient();
        if (client == null) {
            call.reject(CLIENT_UNAVAILABLE);
        }
        return client;
    }

    private static JSObject clientStateJson(
        JmcomicSessionManager.ClientStateSnapshot snapshot) {
        JSObject result = new JSObject();
        result.put("state", snapshot.state());
        if (snapshot.reason() != null) {
            result.put("reason", snapshot.reason());
        }
        result.put("timestamp", snapshot.timestamp());
        return result;
    }

    private static JSObject networkEventJson(JmcomicSessionManager.NetworkEvent event) {
        JSObject result = new JSObject();
        result.put("phase", event.phase());
        result.put("message", event.message());
        result.put("timestamp", event.timestamp());
        if (event.domains() != null) {
            JSObject states = domainStatesJson(event.domains());
            result.put("domains", states.optJSONArray("domains"));
            result.put("alive", states.optInt("alive"));
            result.put("total", states.optInt("total"));
            result.put("allDeadFallback", event.allDeadFallback());
        }
        return result;
    }

    private static JSObject domainStatesJson(Map<String, Integer> states) {
        JSArray domains = new JSArray();
        int alive = 0;
        for (Map.Entry<String, Integer> entry : states.entrySet()) {
            JSObject domain = new JSObject();
            domain.put("domain", entry.getKey());
            boolean reachable = isDomainReachable(entry.getValue());
            domain.put("reachable", reachable);
            if (reachable) alive++;
            domains.put(domain);
        }
        boolean allDeadFallback = !states.isEmpty()
            && states.values().stream().allMatch(value -> value != null && value == -1);
        JSObject result = new JSObject();
        result.put("domains", domains);
        result.put("alive", alive);
        result.put("total", states.size());
        result.put("allDeadFallback", allDeadFallback);
        return result;
    }

    private static boolean isDomainReachable(Integer state) {
        return state != null && state >= 0 && state < (Integer.MAX_VALUE / 2);
    }
}
