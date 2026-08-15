package io.github.jukomu.bridge.handler;

import android.content.Context;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import io.github.jukomu.feature.auth.data.CredentialStore;
import io.github.jukomu.feature.catalog.ApiCallback;
import io.github.jukomu.feature.catalog.ApiService;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.github.jukomu.platform.persistence.SettingsStore;
import okhttp3.Cookie;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 负责认证 Bridge、登录态缓存和加密凭据的协调。
 *
 * <p>网络认证由 {@link ApiService} 异步执行，Cookie 通过 JSON 存入设置数据库。
 */
public final class AuthPluginHandler {

    private static final String TAG = "AuthPluginHandler";
    private static final String AUTH_COOKIES_KEY = "auth_cookies_json";
    private static final String AUTH_USERNAME_KEY = "auth_username";
    private static final String AUTH_USER_INFO_KEY = "auth_user_info_json";

    private final Context context;
    private final ApiService apiService;
    private final Supplier<List<Cookie>> cookieSupplier;

    public AuthPluginHandler(Context context, ApiService apiService,
                             Supplier<List<Cookie>> cookieSupplier) {
        this.context = context;
        this.apiService = apiService;
        this.cookieSupplier = cookieSupplier;
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
            call.setKeepAlive(true);
            apiService.login(username, password, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject userInfo) {
                    try {
                        SettingsStore settingsStore = SettingsStore.getInstance(context);
                        saveAuthState(settingsStore, userInfo);
                        CredentialStore.getInstance(context).save(username, password);
                        call.resolve(JSObject.fromJSONObject(userInfo));
                    } catch (Exception error) {
                        call.reject(error.getMessage(), error);
                    }
                }

                @Override
                public void onError(String message, Exception error) {
                    call.reject(message, error);
                }
            });
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 注销远程会话，并在成功后清除登录态和加密凭据。
     */
    public void logout(PluginCall call) {
        try {
            call.setKeepAlive(true);
            apiService.logout(new ApiCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    try {
                        SettingsStore settingsStore = SettingsStore.getInstance(context);
                        clearAuthState(settingsStore);
                        CredentialStore.getInstance(context).clear();
                        call.resolve(JSObject.fromJSONObject(result));
                    } catch (Exception error) {
                        call.reject(error.getMessage(), error);
                    }
                }

                @Override
                public void onError(String message, Exception error) {
                    call.reject(message, error);
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
            call.setKeepAlive(true);
            apiService.getUserProfile(uid, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    try {
                        call.resolve(JSObject.fromJSONObject(result));
                    } catch (JSONException error) {
                        call.reject(error.getMessage(), error);
                    }
                }

                @Override
                public void onError(String message, Exception error) {
                    call.reject(message, error);
                }
            });
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
        CredentialStore credentialStore = CredentialStore.getInstance(context);
        String username = credentialStore.getUsername();
        String password = credentialStore.getPassword();

        if (username == null || username.isEmpty()
            || password == null || password.isEmpty()) {
            call.reject("自动登录失败：无保存的凭据");
            return;
        }

        call.setKeepAlive(true);
        apiService.login(username, password, new ApiCallback() {
            @Override
            public void onSuccess(JSONObject userInfo) {
                try {
                    SettingsStore settingsStore = SettingsStore.getInstance(context);
                    saveAuthState(settingsStore, userInfo);
                    JSObject result = new JSObject();
                    result.put("success", true);
                    result.put("userInfo", JSObject.fromJSONObject(userInfo));
                    call.resolve(result);
                } catch (Exception error) {
                    call.reject(error.getMessage(), error);
                }
            }

            @Override
            public void onError(String message, Exception error) {
                if (error instanceof ResponseException) {
                    credentialStore.clear();
                }
                call.reject("自动登录失败：凭据无效或已过期");
            }
        });
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
                Log.d(TAG, "跳过无效cookie条目", error);
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
                Log.d(TAG, "跳过损坏的cookie条目", error);
            }
        }
        return cookies;
    }
}
