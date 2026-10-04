package io.github.jukomu.bridge.handler;

import android.content.Context;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import io.github.jukomu.bridge.PluginCallSession;
import io.github.jukomu.feature.auth.data.CredentialStore;
import io.github.jukomu.feature.catalog.ApiCallback;
import io.github.jukomu.feature.catalog.ApiService;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.github.jukomu.platform.persistence.SettingsStore;
import okhttp3.Cookie;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 负责认证 Bridge、登录态缓存和加密凭据的协调。
 *
 * <p>网络认证由 {@link ApiService} 异步执行，Cookie 通过 JSON 存入设置数据库。
 */
public final class AuthPluginHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthPluginHandler.class);
    private static final String AUTH_COOKIES_KEY = "auth_cookies_json";
    private static final String AUTH_USERNAME_KEY = "auth_username";
    private static final String AUTH_USER_INFO_KEY = "auth_user_info_json";

    private final Context context;
    private final ApiService apiService;
    private final Supplier<List<Cookie>> cookieSupplier;
    private final PluginCallSession callSession;
    private volatile String memoryUsername;
    private volatile String memoryPassword;
    private volatile boolean credentialsLoaded;
    private long authGeneration;

    public AuthPluginHandler(Context context, ApiService apiService,
                             Supplier<List<Cookie>> cookieSupplier,
                             PluginCallSession callSession) {
        this.context = context;
        this.apiService = apiService;
        this.cookieSupplier = cookieSupplier;
        this.callSession = callSession;
    }

    /**
     * 删除缓存的 Cookie、用户名和用户信息，不删除加密登录凭据。
     */
    public void clearAuthState(SettingsStore settingsStore) {
        settingsStore.deleteKey(AUTH_COOKIES_KEY);
        settingsStore.deleteKey(AUTH_USERNAME_KEY);
        settingsStore.deleteKey(AUTH_USER_INFO_KEY);
    }

    /**
     * 使用非空用户名和密码登录，并持久化登录态与加密凭据。
     */
    public void login(PluginCall call) {
        try {
            String username = call.getString("username");
            String password = call.getString("password");
            if (username == null || username.isEmpty()
                || password == null || password.isEmpty()) {
                call.reject("username and password are required");
                return;
            }
            final long loginGeneration;
            synchronized (this) {
                loginGeneration = ++authGeneration;
            }
            startAsync(call, trackedCall -> apiService.login(
                username, password, new ApiCallback() {
                    @Override
                    public void onSuccess(JSONObject userInfo) {
                        callSession.completeIfActive(trackedCall, activeCall -> {
                            try {
                                synchronized (AuthPluginHandler.this) {
                                    if (loginGeneration != authGeneration) {
                                        activeCall.reject("认证状态已变化", "cancelled");
                                        return;
                                    }
                                    SettingsStore settingsStore = SettingsStore.getInstance(context);
                                    saveAuthState(settingsStore, userInfo);
                                    memoryUsername = username;
                                    memoryPassword = password;
                                    CredentialStore.getInstance(context).save(username, password);
                                }
                                activeCall.resolve(JSObject.fromJSONObject(userInfo));
                            } catch (Exception error) {
                                activeCall.reject(error.getMessage(), error);
                            }
                        });
                    }

                    @Override
                    public void onError(String message, Exception error) {
                        if (error instanceof ResponseException responseError
                            && isAuthenticationFailure(responseError)) {
                            synchronized (AuthPluginHandler.this) {
                                if (loginGeneration != authGeneration) {
                                    trackedCall.reject(message, "permission-denied", error);
                                    return;
                                }
                                clearAuthState(SettingsStore.getInstance(context));
                            }
                            try {
                                apiService.logout(new ApiCallback() {
                                    @Override public void onSuccess(JSONObject result) { }
                                    @Override public void onError(String logoutMessage, Exception logoutError) {
                                        LOGGER.warn("直接登录认证失败后远端注销失败", logoutError);
                                    }
                                });
                            } catch (RuntimeException logoutError) {
                                LOGGER.warn("直接登录认证失败后无法发起远端注销", logoutError);
                            }
                        }
                        trackedCall.reject(
                            message == null ? "登录失败" : message,
                            error instanceof ResponseException responseError
                                && isAuthenticationFailure(responseError)
                                ? "permission-denied" : errorCode(error),
                            error);
                    }
                }));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 先清除本地登录态和加密凭据，再尝试注销远程会话。
     */
    public void logout(PluginCall call) {
        try {
            startAsync(call, trackedCall -> {
                clearStoredLogin();
                JSObject result = new JSObject();
                result.put("success", true);
                trackedCall.resolve(result);
                try {
                    apiService.logout(new ApiCallback() {
                        @Override
                        public void onSuccess(JSONObject result) {
                            // 本地登出已经完成，远端结果不再影响调用方。
                        }

                        @Override
                        public void onError(String message, Exception error) {
                            LOGGER.warn("远端注销失败，本地登录态已清除", error);
                        }
                    });
                } catch (RuntimeException error) {
                    LOGGER.warn("无法发起远端注销，本地登录态已清除", error);
                }
            });
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按非空用户 ID 异步查询用户资料。
     */
    public void getUserProfile(PluginCall call) {
        try {
            String uid = call.getString("uid");
            if (uid == null || uid.isEmpty()) {
                call.reject("uid is required");
                return;
            }
            startAsync(call, trackedCall -> apiService.getUserProfile(uid, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    try {
                        trackedCall.resolve(JSObject.fromJSONObject(result));
                    } catch (JSONException error) {
                        trackedCall.reject(error.getMessage(), error);
                    }
                }

                @Override
                public void onError(String message, Exception error) {
                    trackedCall.reject(message, error);
                }
            }));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 读取本地登录态；用户信息损坏时清除三项登录缓存并返回未登录。
     */
    public void checkLoginState(PluginCall call) {
        SettingsStore settingsStore = SettingsStore.getInstance(context);
        String username = settingsStore.getString(AUTH_USERNAME_KEY);
        String userInfoJson = settingsStore.getString(AUTH_USER_INFO_KEY);

        JSObject result = new JSObject();
        if (username != null && !username.isEmpty()
            && userInfoJson != null && !userInfoJson.isEmpty()) {
            try {
                result.put("userInfo", JSObject.fromJSONObject(new JSONObject(userInfoJson)));
                result.put("loggedIn", true);
                result.put("username", username);
            } catch (JSONException error) {
                clearAuthState(settingsStore);
                result.put("loggedIn", false);
            }
        } else {
            result.put("loggedIn", false);
        }
        call.resolve(result);
    }

    /**
     * 使用加密凭据自动登录；认证失败时删除凭据，网络错误时保留凭据。
     */
    public void autoLogin(PluginCall call) {
        String[] credentials = loadCredentialsIfNeeded();
        String username = credentials[0];
        String password = credentials[1];

        if (username == null || username.isEmpty()
            || password == null || password.isEmpty()) {
            call.reject("自动登录失败：无保存的凭据", "not-found");
            return;
        }
        final String loginUsername = username;
        final String loginPassword = password;
        final long loginGeneration;
        synchronized (this) {
            loginGeneration = authGeneration;
        }

        startAsync(call, trackedCall -> apiService.login(
            loginUsername, loginPassword, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject userInfo) {
                    callSession.completeIfActive(trackedCall, activeCall -> {
                        try {
                            synchronized (AuthPluginHandler.this) {
                                if (loginGeneration != authGeneration) {
                                    activeCall.reject("认证状态已变化", "cancelled");
                                    return;
                                }
                                SettingsStore settingsStore = SettingsStore.getInstance(context);
                                saveAuthState(settingsStore, userInfo);
                                memoryUsername = loginUsername;
                                memoryPassword = loginPassword;
                            }
                            JSObject result = new JSObject();
                            result.put("success", true);
                            result.put("userInfo", JSObject.fromJSONObject(userInfo));
                            activeCall.resolve(result);
                        } catch (Exception error) {
                            activeCall.reject(error.getMessage(), error);
                        }
                    });
                }

                @Override
                public void onError(String message, Exception error) {
                    callSession.completeIfActive(trackedCall, activeCall -> {
                        synchronized (AuthPluginHandler.this) {
                            if (loginGeneration != authGeneration) {
                                activeCall.reject(message, errorCode(error), error);
                                return;
                            }
                        }
                        if (error instanceof ResponseException responseError
                            && isAuthenticationFailure(responseError)) {
                            clearAuthState(SettingsStore.getInstance(context));
                            try {
                                apiService.logout(new ApiCallback() {
                                    @Override public void onSuccess(JSONObject result) { }
                                    @Override public void onError(String message, Exception error) {
                                        LOGGER.warn("认证失败后远端注销失败", error);
                                    }
                                });
                            } catch (RuntimeException logoutError) {
                                LOGGER.warn("认证失败后无法发起远端注销", logoutError);
                            }
                            activeCall.reject(
                                "自动登录失败：凭据无效或已过期", "permission-denied", error);
                        } else {
                            activeCall.reject(
                                message == null ? "自动登录失败" : message,
                                errorCode(error), error);
                        }
                    });
                }
        }));
    }

    /**
     * 线路切换后只清理 JM 客户端会话，保留应用层内存/安全存储凭据并异步重登。
     */
    public void reauthenticateAfterRouteChange() {
        clearAuthState(SettingsStore.getInstance(context));
        String[] credentials = loadCredentialsIfNeeded();
        String username = credentials[0];
        String password = credentials[1];
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) return;
        final String loginUsername = username;
        final String loginPassword = password;
        final long routeGeneration;
        synchronized (this) {
            routeGeneration = ++authGeneration;
        }
        try {
            apiService.logout(new ApiCallback() {
                @Override public void onSuccess(JSONObject result) {
                    loginAfterRoute(loginUsername, loginPassword, routeGeneration);
                }
                @Override public void onError(String message, Exception error) {
                    loginAfterRoute(loginUsername, loginPassword, routeGeneration);
                }
            });
        } catch (RuntimeException error) {
            LOGGER.warn("线路切换后无法清理旧认证态", error);
            loginAfterRoute(loginUsername, loginPassword, routeGeneration);
        }
    }

    private void loginAfterRoute(String username, String password, long expectedGeneration) {
        synchronized (this) {
            if (expectedGeneration != authGeneration) return;
        }
        try {
            apiService.login(username, password, new ApiCallback() {
                @Override public void onSuccess(JSONObject userInfo) {
                    try {
                        synchronized (AuthPluginHandler.this) {
                            if (expectedGeneration != authGeneration) return;
                            saveAuthState(SettingsStore.getInstance(context), userInfo);
                            memoryUsername = username;
                            memoryPassword = password;
                        }
                    } catch (Exception error) {
                        LOGGER.warn("线路切换后保存认证态失败", error);
                    }
                }

                @Override public void onError(String message, Exception error) {
                    if (error instanceof ResponseException responseError
                        && isAuthenticationFailure(responseError)) {
                        synchronized (AuthPluginHandler.this) {
                            if (expectedGeneration != authGeneration) return;
                        }
                        clearAuthState(SettingsStore.getInstance(context));
                        try {
                            apiService.logout(new ApiCallback() {
                                @Override public void onSuccess(JSONObject result) { }
                                @Override public void onError(String message, Exception error) { }
                            });
                        } catch (RuntimeException logoutError) {
                            LOGGER.warn("线路切换认证失败后无法注销", logoutError);
                        }
                    }
                }
            });
        } catch (RuntimeException error) {
            LOGGER.warn("线路切换后自动登录启动失败", error);
        }
    }

    private static boolean isAuthenticationFailure(ResponseException error) {
        int status = error.getErrorCode();
        return status == 401 || status == 403;
    }

    private static String errorCode(Exception error) {
        if (!(error instanceof ResponseException responseError)) return "network";
        return responseError.getErrorCode() >= 500 ? "network" : "internal";
    }

    private void startAsync(PluginCall call, Consumer<PluginCall> starter) {
        PluginCall trackedCall = callSession.register(call);
        if (trackedCall == null) {
            return;
        }
        try {
            trackedCall.setKeepAlive(true);
            starter.accept(trackedCall);
        } catch (RuntimeException error) {
            trackedCall.reject(error.getMessage(), error);
        }
    }

    private void saveAuthState(SettingsStore settingsStore, JSONObject userInfo)
        throws JSONException {
        settingsStore.putString(
            AUTH_COOKIES_KEY,
            cookiesToJson(cookieSupplier.get()).toString()
        );
        settingsStore.putString(AUTH_USERNAME_KEY, userInfo.getString("username"));
        settingsStore.putString(AUTH_USER_INFO_KEY, userInfo.toString());
    }

    private void clearStoredLogin() {
        synchronized (this) {
            authGeneration++;
            memoryUsername = null;
            memoryPassword = null;
            credentialsLoaded = true;
        }
        CredentialStore.getInstance(context).clear();
        clearAuthState(SettingsStore.getInstance(context));
    }

    private synchronized String[] loadCredentialsIfNeeded() {
        if (memoryUsername != null && !memoryUsername.isEmpty()
            && memoryPassword != null && !memoryPassword.isEmpty()) {
            return new String[]{memoryUsername, memoryPassword};
        }
        if (!credentialsLoaded) {
            CredentialStore credentialStore = CredentialStore.getInstance(context);
            memoryUsername = credentialStore.getUsername();
            memoryPassword = credentialStore.getPassword();
            credentialsLoaded = true;
        }
        return new String[]{memoryUsername, memoryPassword};
    }

    private static JSONArray cookiesToJson(List<Cookie> cookies) {
        JSONArray result = new JSONArray();
        for (Cookie cookie : cookies) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", cookie.name());
                item.put("value", cookie.value());
                item.put("domain", cookie.domain());
                item.put("path", cookie.path());
                item.put("expiresAt", cookie.expiresAt());
                item.put("secure", cookie.secure());
                item.put("httpOnly", cookie.httpOnly());
                item.put("persistent", cookie.persistent());
                result.put(item);
            } catch (JSONException error) {
                LOGGER.debug("跳过无效cookie条目", error);
            }
        }
        return result;
    }

    /**
     * 将未过期的 Cookie JSON 条目转换为网络客户端 Cookie。
     */
    private static List<Cookie> parseCookiesFromJson(JSONArray items) {
        List<Cookie> cookies = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int index = 0; index < items.length(); index++) {
            try {
                JSONObject item = items.getJSONObject(index);
                long expiresAt = item.optLong("expiresAt", 0);
                if (expiresAt > 0 && expiresAt < now) {
                    continue;
                }

                Cookie.Builder builder = new Cookie.Builder()
                    .name(item.getString("name"))
                    .value(item.getString("value"))
                    .domain(item.getString("domain"))
                    .path(item.optString("path", "/"))
                    .expiresAt(expiresAt);
                if (item.optBoolean("secure", false)) {
                    builder.secure();
                }
                if (item.optBoolean("httpOnly", false)) {
                    builder.httpOnly();
                }
                if (item.optBoolean("persistent", false)) {
                    cookies.add(builder.build());
                } else {
                    cookies.add(builder.hostOnlyDomain(item.getString("domain")).build());
                }
            } catch (Exception error) {
                LOGGER.debug("跳过损坏的cookie条目", error);
            }
        }
        return cookies;
    }
}
