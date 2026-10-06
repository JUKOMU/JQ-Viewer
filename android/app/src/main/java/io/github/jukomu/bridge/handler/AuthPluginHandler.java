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
import java.util.concurrent.atomic.AtomicLong;
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
    private boolean sessionActive = true;
    private long authGeneration;
    private final AtomicLong authAttemptSequence = new AtomicLong();

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
     * 使绑定客户端上的后台认证回调失效。
     */
    public synchronized void invalidateSession() {
        sessionActive = false;
        authGeneration++;
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
            final long attemptId = authAttemptSequence.incrementAndGet();
            synchronized (this) {
                loginGeneration = ++authGeneration;
            }
            LOGGER.info("认证登录开始 attemptId={} usernameHash={} generation={}",
                attemptId, stableHash(username), loginGeneration);
            startAsync(call, trackedCall -> apiService.login(
                username, password, new ApiCallback() {
                    @Override
                    public void onSuccess(JSONObject userInfo) {
                        callSession.completeIfActive(trackedCall, activeCall -> {
                            try {
                                synchronized (AuthPluginHandler.this) {
                                    if (loginGeneration != authGeneration) {
                                        LOGGER.info("认证登录取消 attemptId={} reason=generation_changed",
                                            attemptId);
                                        activeCall.reject("认证状态已变化", "cancelled");
                                        return;
                                    }
                                    SettingsStore settingsStore = SettingsStore.getInstance(context);
                                    saveAuthState(settingsStore, userInfo);
                                    memoryUsername = username;
                                    memoryPassword = password;
                                    CredentialStore.getInstance(context).save(username, password);
                                }
                                LOGGER.info("认证登录成功 attemptId={} usernameHash={}",
                                    attemptId, stableHash(username));
                                activeCall.resolve(JSObject.fromJSONObject(userInfo));
                            } catch (Exception error) {
                                activeCall.reject(error.getMessage(), error);
                            }
                        });
                    }

                    @Override
                    public void onError(String message, Exception error) {
                        LOGGER.warn("认证登录失败 attemptId={} usernameHash={} errorType={}",
                            attemptId, stableHash(username), errorType(error));
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
                                    @Override
                                    public void onSuccess(JSONObject result) {
                                    }

                                    @Override
                                    public void onError(String logoutMessage, Exception logoutError) {
                                        LOGGER.warn("直接登录认证失败后远端注销失败 errorType={}",
                                            errorType(logoutError));
                                    }
                                });
                            } catch (RuntimeException logoutError) {
                                LOGGER.warn("直接登录认证失败后无法发起远端注销 errorType={}",
                                    errorType(logoutError));
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
                long attemptId = authAttemptSequence.incrementAndGet();
                LOGGER.info("认证注销开始 attemptId={}", attemptId);
                clearStoredLogin();
                JSObject result = new JSObject();
                result.put("success", true);
                trackedCall.resolve(result);
                LOGGER.info("认证注销本地状态已清除 attemptId={}", attemptId);
                try {
                    apiService.logout(new ApiCallback() {
                        @Override
                        public void onSuccess(JSONObject result) {
                            LOGGER.info("认证注销远端完成 attemptId={}", attemptId);
                            // 本地登出已经完成，远端结果不再影响调用方。
                        }

                        @Override
                        public void onError(String message, Exception error) {
                            LOGGER.warn("认证注销远端失败 attemptId={} errorType={}",
                                attemptId, errorType(error));
                            LOGGER.warn("远端注销失败，本地登录态已清除 errorType={}",
                                errorType(error));
                        }
                    });
                } catch (RuntimeException error) {
                    LOGGER.warn("认证注销远端启动失败 attemptId={} errorType={}",
                        attemptId, errorType(error));
                    LOGGER.warn("无法发起远端注销，本地登录态已清除 errorType={}",
                        errorType(error));
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
        final long attemptId = authAttemptSequence.incrementAndGet();
        final long loginGeneration;
        synchronized (this) {
            loginGeneration = authGeneration;
        }
        LOGGER.info("自动登录开始 attemptId={} usernameHash={} generation={}",
            attemptId, stableHash(loginUsername), loginGeneration);

        startAsync(call, trackedCall -> apiService.login(
            loginUsername, loginPassword, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject userInfo) {
                    callSession.completeIfActive(trackedCall, activeCall -> {
                        try {
                            synchronized (AuthPluginHandler.this) {
                                if (loginGeneration != authGeneration) {
                                    LOGGER.info("自动登录取消 attemptId={} reason=generation_changed",
                                        attemptId);
                                    activeCall.reject("认证状态已变化", "cancelled");
                                    return;
                                }
                                SettingsStore settingsStore = SettingsStore.getInstance(context);
                                saveAuthState(settingsStore, userInfo);
                                memoryUsername = loginUsername;
                                memoryPassword = loginPassword;
                            }
                            LOGGER.info("自动登录成功 attemptId={} usernameHash={}",
                                attemptId, stableHash(loginUsername));
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
                    LOGGER.warn("自动登录失败 attemptId={} usernameHash={} errorType={}",
                        attemptId, stableHash(loginUsername), errorType(error));
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
                                    @Override
                                    public void onSuccess(JSONObject result) {
                                    }

                                    @Override
                                    public void onError(String message, Exception error) {
                                        LOGGER.warn("认证失败后远端注销失败 errorType={}",
                                            errorType(error));
                                    }
                                });
                            } catch (RuntimeException logoutError) {
                                LOGGER.warn("认证失败后无法发起远端注销 errorType={}",
                                    errorType(logoutError));
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
        final String[] credentials;
        final long routeGeneration;
        synchronized (this) {
            if (!sessionActive) {
                LOGGER.info("线路切换后认证恢复跳过 reason=session_inactive");
                return;
            }
            clearAuthState(SettingsStore.getInstance(context));
            credentials = loadCredentialsIfNeeded();
            routeGeneration = ++authGeneration;
        }
        String username = credentials[0];
        String password = credentials[1];
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            LOGGER.info("线路切换后认证恢复跳过 reason=no_credentials");
            return;
        }
        final String loginUsername = username;
        final String loginPassword = password;
        final long attemptId = authAttemptSequence.incrementAndGet();
        LOGGER.info("线路切换后认证恢复开始 attemptId={} usernameHash={} generation={}",
            attemptId, stableHash(loginUsername), routeGeneration);
        try {
            apiService.logout(new ApiCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    LOGGER.info("线路切换后旧会话清理完成 attemptId={}", attemptId);
                    loginAfterRoute(loginUsername, loginPassword, routeGeneration, attemptId);
                }

                @Override
                public void onError(String message, Exception error) {
                    LOGGER.warn("线路切换后旧会话清理失败，继续重登 attemptId={} errorType={}",
                        attemptId, errorType(error));
                    loginAfterRoute(loginUsername, loginPassword, routeGeneration, attemptId);
                }
            });
        } catch (RuntimeException error) {
            LOGGER.warn("线路切换后无法清理旧认证态 errorType={}", errorType(error));
            loginAfterRoute(loginUsername, loginPassword, routeGeneration, attemptId);
        }
    }

    private void loginAfterRoute(String username, String password, long expectedGeneration,
                                 long attemptId) {
        synchronized (this) {
            if (!sessionActive || expectedGeneration != authGeneration) {
                LOGGER.info("线路切换后认证登录跳过 reason=stale_generation generation={}",
                    expectedGeneration);
                return;
            }
        }
        LOGGER.info("线路切换后认证登录发起 attemptId={} usernameHash={} generation={}",
            attemptId, stableHash(username), expectedGeneration);
        try {
            apiService.login(username, password, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject userInfo) {
                    try {
                        synchronized (AuthPluginHandler.this) {
                            if (!sessionActive || expectedGeneration != authGeneration) return;
                            saveAuthState(SettingsStore.getInstance(context), userInfo);
                            memoryUsername = username;
                            memoryPassword = password;
                            LOGGER.info("线路切换后认证恢复成功 attemptId={} usernameHash={}",
                                attemptId, stableHash(username));
                        }
                    } catch (Exception error) {
                        LOGGER.warn("线路切换后保存认证态失败 errorType={}", errorType(error));
                    }
                }

                @Override
                public void onError(String message, Exception error) {
                    LOGGER.warn("线路切换后认证恢复失败 attemptId={} errorType={}",
                        attemptId, errorType(error));
                    if (error instanceof ResponseException responseError
                        && isAuthenticationFailure(responseError)) {
                        synchronized (AuthPluginHandler.this) {
                            if (!sessionActive || expectedGeneration != authGeneration) return;
                            clearAuthState(SettingsStore.getInstance(context));
                        }
                        try {
                            apiService.logout(new ApiCallback() {
                                @Override
                                public void onSuccess(JSONObject result) {
                                }

                                @Override
                                public void onError(String message, Exception error) {
                                }
                            });
                        } catch (RuntimeException logoutError) {
                            LOGGER.warn("线路切换认证失败后无法注销 errorType={}",
                                errorType(logoutError));
                        }
                    }
                }
            });
        } catch (RuntimeException error) {
            LOGGER.warn("线路切换后自动登录启动失败 errorType={}", errorType(error));
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

    private static String stableHash(String value) {
        if (value == null || value.isEmpty()) return "empty";
        return Integer.toHexString(value.hashCode());
    }

    private static String errorType(Exception error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
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
                LOGGER.debug("跳过无效cookie条目 errorType={}", errorType(error));
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
                LOGGER.debug("跳过损坏的cookie条目 errorType={}", errorType(error));
            }
        }
        return cookies;
    }
}
