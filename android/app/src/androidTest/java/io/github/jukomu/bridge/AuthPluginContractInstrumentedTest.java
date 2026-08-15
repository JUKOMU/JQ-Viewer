package io.github.jukomu.bridge;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import io.github.jukomu.bridge.handler.AuthPluginHandler;
import io.github.jukomu.feature.auth.data.CredentialStore;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.github.jukomu.platform.persistence.SettingsStore;
import okhttp3.Cookie;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.*;

public class AuthPluginContractInstrumentedTest {

    private IsolatedDatabaseContext context;
    private JmcomicPlugin plugin;
    private FakeApiService apiService;
    private AuthPluginHandler authHandler;
    private SettingsStore settingsStore;
    private CredentialStore credentialStore;

    @Before
    public void setUp() throws Exception {
        resetSingleton(SettingsStore.class, "instance");
        resetSingleton(CredentialStore.class, "instance");
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(targetContext.getCacheDir(),
            "auth-plugin-contract-" + System.nanoTime());
        context = new IsolatedDatabaseContext(targetContext, directory);
        plugin = new ContextPlugin(context);
        apiService = new FakeApiService();
        Cookie cookie = new Cookie.Builder()
            .name("session")
            .value("token")
            .domain("example.com")
            .path("/")
            .build();
        authHandler = new AuthPluginHandler(
            context,
            apiService,
            () -> Collections.singletonList(cookie)
        );
        injectAuthHandler(plugin, authHandler);
        settingsStore = SettingsStore.getInstance(context);
        credentialStore = CredentialStore.getInstance(context);
        credentialStore.clear();
    }

    @After
    public void tearDown() throws Exception {
        credentialStore.clear();
        resetSingleton(CredentialStore.class, "instance");
        settingsStore.close();
        resetSingleton(SettingsStore.class, "instance");
        context.deleteTestDatabases();
    }

    @Test
    public void requiredInputsAndMissingCredentialsRejectBeforeKeepAlive() throws Exception {
        RecordingPluginCall login = call("login");
        plugin.login(login);
        assertRejected(login, "username and password are required", false);

        RecordingPluginCall profile = call("getUserProfile");
        plugin.getUserProfile(profile);
        assertRejected(profile, "uid is required", false);

        RecordingPluginCall autoLogin = call("autoLogin");
        plugin.autoLogin(autoLogin);
        assertRejected(autoLogin, "自动登录失败：无保存的凭据", false);

        RecordingPluginCall state = call("checkLoginState");
        plugin.checkLoginState(state);
        assertFalse(state.resolvedData.getBoolean("loggedIn"));
        assertEquals(1, state.completionCount);
        assertFalse(state.isKeptAlive());

        RecordingPluginCall validProfile = call("getUserProfile", "uid", "user-1");
        plugin.getUserProfile(validProfile);
        assertEquals("getUserProfile", apiService.method);
        assertEquals(Collections.singletonList("user-1"), apiService.arguments);
        assertTrue(validProfile.isKeptAlive());
        assertEquals(1, validProfile.completionCount);
    }

    @Test
    public void loginPersistsStateAndLogoutClearsIt() throws Exception {
        JSONObject userInfo = new JSONObject();
        userInfo.put("username", "alice");
        userInfo.put("uid", "1");
        apiService.succeedWith(userInfo);

        RecordingPluginCall login = call(
            "login", "username", "alice", "password", "secret");
        plugin.login(login);

        assertEquals("alice", login.resolvedData.getString("username"));
        assertTrue(login.isKeptAlive());
        assertEquals("alice", settingsStore.getString("auth_username"));
        assertEquals(userInfo.toString(), settingsStore.getString("auth_user_info_json"));
        JSONArray cookies = new JSONArray(settingsStore.getString("auth_cookies_json"));
        assertEquals(1, cookies.length());
        assertEquals("session", cookies.getJSONObject(0).getString("name"));
        assertEquals("token", cookies.getJSONObject(0).getString("value"));
        assertEquals("alice", credentialStore.getUsername());
        assertEquals("secret", credentialStore.getPassword());

        RecordingPluginCall state = call("checkLoginState");
        plugin.checkLoginState(state);
        assertTrue(state.resolvedData.getBoolean("loggedIn"));
        assertEquals("alice", state.resolvedData.getString("username"));
        assertEquals("1", state.resolvedData.getJSONObject("userInfo").getString("uid"));
        assertFalse(state.isKeptAlive());

        apiService.succeedWith(new JSONObject().put("success", true));
        RecordingPluginCall logout = call("logout");
        plugin.logout(logout);

        assertTrue(logout.resolvedData.getBoolean("success"));
        assertTrue(logout.isKeptAlive());
        assertNull(settingsStore.getString("auth_cookies_json"));
        assertNull(settingsStore.getString("auth_username"));
        assertNull(settingsStore.getString("auth_user_info_json"));
        assertNull(credentialStore.getUsername());
        assertNull(credentialStore.getPassword());
    }

    @Test
    public void damagedUserInfoIsTreatedAsLoggedOutAndCleared() throws Exception {
        settingsStore.putString("auth_cookies_json", "[]");
        settingsStore.putString("auth_username", "alice");
        settingsStore.putString("auth_user_info_json", "not-json");
        RecordingPluginCall call = call("checkLoginState");

        plugin.checkLoginState(call);

        assertFalse(call.resolvedData.getBoolean("loggedIn"));
        assertNull(settingsStore.getString("auth_cookies_json"));
        assertNull(settingsStore.getString("auth_username"));
        assertNull(settingsStore.getString("auth_user_info_json"));
        assertFalse(call.isKeptAlive());
    }

    @Test
    public void clearAuthStateDoesNotDeleteEncryptedCredentials() {
        settingsStore.putString("auth_cookies_json", "[]");
        settingsStore.putString("auth_username", "alice");
        settingsStore.putString("auth_user_info_json", "{}");
        settingsStore.putString("unrelated", "value");
        credentialStore.save("alice", "secret");

        authHandler.clearAuthState(settingsStore);

        assertNull(settingsStore.getString("auth_cookies_json"));
        assertNull(settingsStore.getString("auth_username"));
        assertNull(settingsStore.getString("auth_user_info_json"));
        assertEquals("value", settingsStore.getString("unrelated"));
        assertEquals("alice", credentialStore.getUsername());
        assertEquals("secret", credentialStore.getPassword());
    }

    @Test
    public void autoLoginPersistsSuccessAndAppliesCredentialFailurePolicy() throws Exception {
        credentialStore.save("alice", "secret");
        JSONObject userInfo = new JSONObject();
        userInfo.put("username", "alice");
        userInfo.put("uid", "1");
        apiService.succeedWith(userInfo);
        RecordingPluginCall success = call("autoLogin");

        plugin.autoLogin(success);

        assertTrue(success.resolvedData.getBoolean("success"));
        assertEquals("alice",
            success.resolvedData.getJSONObject("userInfo").getString("username"));
        assertTrue(success.isKeptAlive());
        assertEquals("alice", settingsStore.getString("auth_username"));

        credentialStore.save("alice", "secret");
        apiService.failWith("network unavailable", new IOException("network unavailable"));
        RecordingPluginCall networkFailure = call("autoLogin");
        plugin.autoLogin(networkFailure);
        assertRejected(networkFailure, "自动登录失败：凭据无效或已过期", true);
        assertEquals("alice", credentialStore.getUsername());

        apiService.failWith("unauthorized", new ResponseException("unauthorized"));
        RecordingPluginCall authFailure = call("autoLogin");
        plugin.autoLogin(authFailure);
        assertRejected(authFailure, "自动登录失败：凭据无效或已过期", true);
        assertNull(credentialStore.getUsername());
        assertNull(credentialStore.getPassword());
    }

    private static void assertRejected(RecordingPluginCall call, String message,
                                       boolean keptAlive) {
        assertEquals(message, call.rejectionMessage);
        assertEquals(1, call.completionCount);
        assertEquals(keptAlive, call.isKeptAlive());
    }

    private static RecordingPluginCall call(String methodName, Object... entries) {
        JSObject data = new JSObject();
        for (int index = 0; index < entries.length; index += 2) {
            data.put((String) entries[index], entries[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static void injectAuthHandler(JmcomicPlugin plugin,
                                          AuthPluginHandler handler) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("authHandler");
        field.setAccessible(true);
        field.set(plugin, handler);
    }

    private static void resetSingleton(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, null);
    }

    private static final class ContextPlugin extends JmcomicPlugin {

        private final Context context;

        private ContextPlugin(Context context) {
            this.context = context;
        }

        @Override
        public Context getContext() {
            return context;
        }
    }
}
