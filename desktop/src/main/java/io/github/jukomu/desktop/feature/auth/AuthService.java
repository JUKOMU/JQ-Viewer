package io.github.jukomu.desktop.feature.auth;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.auth.model.AutoLoginResponse;
import io.github.jukomu.desktop.feature.auth.model.LoginStateResponse;
import io.github.jukomu.desktop.feature.auth.model.UserInfoResponse;
import io.github.jukomu.desktop.feature.auth.model.UserProfileResponse;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.exception.NetworkException;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import io.github.jukomu.jmcomic.api.model.JmUserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 协调当前登录会话与操作系统安全凭据。
 */
public final class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private final Supplier<JmClient> clientSupplier;
    private final CredentialStore credentials;
    private final Executor remoteLogoutExecutor;
    private final Object remoteAuthLock = new Object();
    private volatile long successfulLoginGeneration;
    private volatile UserInfoResponse userInfo;
    private volatile LoginCredentials memoryCredentials;
    private volatile boolean credentialsLoaded;
    private volatile long authGeneration;

    public AuthService(JmClient client, CredentialStore credentials) {
        this(() -> Objects.requireNonNull(client, "client"), credentials, Runnable::run);
    }

    public AuthService(Supplier<JmClient> clientSupplier, CredentialStore credentials) {
        this(clientSupplier, credentials, Runnable::run);
    }

    public AuthService(
        Supplier<JmClient> clientSupplier,
        CredentialStore credentials,
        Executor remoteLogoutExecutor
    ) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.remoteLogoutExecutor = Objects.requireNonNull(
            remoteLogoutExecutor, "remoteLogoutExecutor");
    }

    public UserInfoResponse login(String username, String password) {
        long started = System.nanoTime();
        long expectedGeneration = nextAuthGeneration();
        UserInfoResponse result;
        try {
            result = remoteLogin(username, password);
        } catch (NetworkException failure) {
            logFailure("login", "network", started, failure);
            throw ApiException.network(message(failure, "登录网络请求失败"));
        } catch (ResponseException failure) {
            logFailure("login", responseError(failure), started, failure);
            if (isAuthenticationFailure(failure)) {
                if (expectedGeneration != authGeneration) {
                    throw ApiException.cancelled("认证状态已变化");
                }
                userInfo = null;
                logoutClientQuietly();
            }
            throw mapResponseFailure(failure, "用户名或密码错误");
        }
        synchronized (this) {
            if (expectedGeneration != authGeneration) {
                throw ApiException.cancelled("认证状态已变化");
            }
            userInfo = result;
            memoryCredentials = new LoginCredentials(username, password);
            saveCredentials(username, password);
        }
        LOGGER.info("auth_request operation=login status=success elapsedMs={}", elapsedMs(started));
        return result;
    }

    /**
     * 立即清除本地会话与凭据，远端注销仅作为后台尽力操作。
     */
    public SuccessResponse logout() {
        long started = System.nanoTime();
        nextAuthGeneration();
        userInfo = null;
        clearMemoryCredentials();
        clearCredentials("退出后无法清除自动登录凭据");
        scheduleRemoteLogout();
        LOGGER.info("auth_request operation=logout status=success elapsedMs={}", elapsedMs(started));
        return SuccessResponse.ok();
    }

    public AutoLoginResponse autoLogin() {
        long started = System.nanoTime();
        long expectedGeneration = currentAuthGeneration();
        LoginCredentials saved = memoryCredentials;
        if (saved == null) {
            saved = loadCredentialsOnce();
        }
        if (saved == null || saved.username() == null || saved.username().isBlank()
            || saved.password() == null || saved.password().isEmpty()) {
            LOGGER.warn("auth_request operation=autoLogin status=failed error=credentials_missing elapsedMs={}",
                elapsedMs(started));
            throw ApiException.notFound("没有保存的自动登录凭据");
        }

        try {
            UserInfoResponse result = remoteLogin(saved.username(), saved.password());
            synchronized (this) {
                if (expectedGeneration != authGeneration) {
                    throw ApiException.cancelled("认证状态已变化");
                }
                userInfo = result;
                memoryCredentials = saved;
            }
            LOGGER.info("auth_request operation=autoLogin status=success elapsedMs={}", elapsedMs(started));
            return new AutoLoginResponse(true, result);
        } catch (NetworkException failure) {
            logFailure("autoLogin", "network", started, failure);
            throw ApiException.network(message(failure, "自动登录网络请求失败"));
        } catch (ResponseException failure) {
            logFailure("autoLogin", responseError(failure), started, failure);
            boolean authenticationFailure = isAuthenticationFailure(failure);
            if (authenticationFailure) {
                if (expectedGeneration != authGeneration) {
                    throw ApiException.cancelled("认证状态已变化");
                }
                userInfo = null;
                logoutClientQuietly();
            }
            throw mapResponseFailure(
                failure,
                authenticationFailure ? "自动登录失败：凭据无效或已过期" : "自动登录失败");
        }
    }

    /**
     * 线路切换后只清理 JM 客户端的远端会话，再复用应用层凭据登录。
     * 应用层凭据不能因线路切换而清除。
     */
    public AutoLoginResponse reauthenticateAfterRouteChange() {
        long started = System.nanoTime();
        long expectedGeneration = nextAuthGeneration();
        userInfo = null;
        logoutClientQuietly();
        try {
            AutoLoginResponse result = autoLogin(expectedGeneration);
            LOGGER.info("auth_request operation=reauthenticate status=success elapsedMs={}", elapsedMs(started));
            return result;
        } catch (RuntimeException failure) {
            LOGGER.warn("auth_request operation=reauthenticate status=failed error={} errorClass={} elapsedMs={}",
                errorCategory(failure), failure.getClass().getSimpleName(), elapsedMs(started));
            throw failure;
        }
    }

    private AutoLoginResponse autoLogin(long expectedGeneration) {
        LoginCredentials saved = memoryCredentials;
        if (saved == null) saved = loadCredentialsOnce();
        if (saved == null || saved.username() == null || saved.username().isBlank()
            || saved.password() == null || saved.password().isEmpty()) {
            throw ApiException.notFound("没有保存的自动登录凭据");
        }
        try {
            UserInfoResponse result = remoteLogin(saved.username(), saved.password());
            synchronized (this) {
                if (expectedGeneration != authGeneration) {
                    throw ApiException.cancelled("认证状态已变化");
                }
                userInfo = result;
                memoryCredentials = saved;
            }
            return new AutoLoginResponse(true, result);
        } catch (NetworkException failure) {
            throw ApiException.network(message(failure, "自动登录网络请求失败"));
        } catch (ResponseException failure) {
            boolean authenticationFailure = isAuthenticationFailure(failure);
            if (authenticationFailure && expectedGeneration == currentAuthGeneration()) {
                userInfo = null;
                logoutClientQuietly();
            }
            throw mapResponseFailure(
                failure,
                authenticationFailure ? "自动登录失败：凭据无效或已过期" : "自动登录失败");
        }
    }

    public LoginStateResponse state() {
        UserInfoResponse currentUserInfo = userInfo;
        return currentUserInfo == null
            ? new LoginStateResponse(false, null, null)
            : new LoginStateResponse(true, currentUserInfo.username(), currentUserInfo);
    }

    public UserProfileResponse profile(String uid) {
        long started = System.nanoTime();
        try {
            JmUserProfile profile = requireClient().getUserProfile(uid);
            UserProfileResponse result = new UserProfileResponse(
                text(profile.username()),
                text(profile.email()),
                text(profile.nickname()),
                text(profile.birthday()),
                text(profile.city()),
                text(profile.country()),
                text(profile.occupation()),
                text(profile.aboutMe()),
                text(profile.website())
            );
            LOGGER.info("auth_request operation=profile status=success elapsedMs={}", elapsedMs(started));
            return result;
        } catch (RuntimeException failure) {
            LOGGER.warn("auth_request operation=profile status=failed error={} errorClass={} elapsedMs={}",
                errorCategory(failure), failure.getClass().getSimpleName(), elapsedMs(started));
            throw failure;
        }
    }

    private void saveCredentials(String username, String password) {
        if (!credentials.isAvailable()) return;
        try {
            credentials.save(username, password);
        } catch (RuntimeException failure) {
            LOGGER.warn("auth_credentials operation=save status=failed errorClass={}",
                failure.getClass().getSimpleName());
        }
    }

    private LoginCredentials loadCredentialsOnce() {
        if (credentialsLoaded) return memoryCredentials;
        synchronized (this) {
            if (credentialsLoaded) return memoryCredentials;
            if (!credentials.isAvailable()) {
                throw ApiException.unavailable("操作系统安全凭据存储不可用，无法自动登录");
            }
            try {
                LoginCredentials loaded = credentials.load();
                memoryCredentials = loaded;
                credentialsLoaded = true;
                return loaded;
            } catch (RuntimeException failure) {
                throw ApiException.unavailable("无法读取操作系统安全凭据，自动登录不可用");
            }
        }
    }

    private long nextAuthGeneration() {
        synchronized (this) {
            return ++authGeneration;
        }
    }

    private long currentAuthGeneration() {
        synchronized (this) {
            return authGeneration;
        }
    }

    private void clearMemoryCredentials() {
        memoryCredentials = null;
        credentialsLoaded = true;
    }

    private void clearCredentialsQuietly() {
        if (!credentials.isAvailable()) return;
        try {
            credentials.clear();
        } catch (RuntimeException failure) {
            LOGGER.warn("auth_credentials operation=clear status=failed errorClass={}",
                failure.getClass().getSimpleName());
        }
    }

    private void logoutClientQuietly() {
        try {
            JmClient client = clientSupplier.get();
            if (client != null) client.logout();
        } catch (RuntimeException failure) {
            LOGGER.warn("auth_request operation=remoteLogout status=failed errorClass={}",
                failure.getClass().getSimpleName());
        }
    }

    private void clearCredentials(String failureMessage) {
        if (!credentials.isAvailable()) return;
        try {
            credentials.clear();
        } catch (RuntimeException failure) {
            throw ApiException.unavailable(failureMessage);
        }
    }

    private void scheduleRemoteLogout() {
        long expectedLoginGeneration = successfulLoginGeneration;
        JmClient client;
        try {
            client = clientSupplier.get();
        } catch (RuntimeException failure) {
            LOGGER.warn("auth_request operation=remoteLogout status=client_unavailable errorClass={}",
                failure.getClass().getSimpleName());
            return;
        }
        if (client == null) return;

        try {
            remoteLogoutExecutor.execute(() -> {
                synchronized (remoteAuthLock) {
                    if (successfulLoginGeneration != expectedLoginGeneration) return;
                    try {
                        client.logout();
                    } catch (RuntimeException failure) {
                        LOGGER.warn("auth_request operation=remoteLogout status=failed errorClass={}",
                            failure.getClass().getSimpleName());
                    }
                }
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("auth_request operation=remoteLogout status=submit_failed errorClass={}",
                failure.getClass().getSimpleName());
        }
    }

    private UserInfoResponse remoteLogin(String username, String password) {
        synchronized (remoteAuthLock) {
            UserInfoResponse result = toUserInfoResponse(
                requireClient().login(username, password));
            successfulLoginGeneration++;
            return result;
        }
    }

    private static String message(RuntimeException failure, String fallback) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? fallback
            : failure.getMessage();
    }

    private static boolean isAuthenticationFailure(ResponseException failure) {
        int status = failure.getErrorCode();
        return status == 401 || status == 403;
    }

    private static ApiException mapResponseFailure(ResponseException failure, String fallback) {
        int status = failure.getErrorCode();
        String message = message(failure, fallback);
        if (status == 401 || status == 403) return ApiException.permissionDenied(message);
        if (status >= 500) return ApiException.network(message);
        return new ApiException("internal", status > 0 ? status : 500, message);
    }

    private JmClient requireClient() {
        JmClient client = clientSupplier.get();
        if (client == null) throw ApiException.unavailable("在线客户端不可用");
        return client;
    }

    private static UserInfoResponse toUserInfoResponse(JmUserInfo info) {
        return new UserInfoResponse(
            text(info.getUid()),
            text(info.getUsername()),
            text(info.getEmail()),
            info.isEmailVerified(),
            text(info.getPhotoUrl()),
            text(info.getFirstName()),
            text(info.getGender()),
            text(info.getMessage()),
            info.getLevel(),
            text(info.getLevelName()),
            info.getNextLevelExp(),
            info.getCurrentExp(),
            info.getExpPercent(),
            info.getCoin(),
            info.getAlbumFavorites(),
            info.getMaxAlbumFavorites()
        );
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private void logFailure(String operation, String error, long started, RuntimeException failure) {
        LOGGER.warn("auth_request operation={} status=failed error={} errorClass={} elapsedMs={}",
            operation, error, failure.getClass().getSimpleName(), elapsedMs(started));
    }

    private static String responseError(ResponseException failure) {
        int status = failure.getErrorCode();
        return status == 401 || status == 403
            ? "authentication" : status >= 500 ? "server" : "response";
    }

    private static String errorCategory(RuntimeException failure) {
        if (failure instanceof ApiException apiException) return apiException.code();
        return "runtime";
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
