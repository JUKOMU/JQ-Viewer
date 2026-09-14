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

/** 协调当前登录会话与操作系统安全凭据。 */
public final class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private final JmClient client;
    private final CredentialStore credentials;
    private volatile UserInfoResponse userInfo;

    public AuthService(JmClient client, CredentialStore credentials) {
        this.client = Objects.requireNonNull(client, "client");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    public UserInfoResponse login(String username, String password) {
        UserInfoResponse result;
        try {
            result = toUserInfoResponse(client.login(username, password));
        } catch (NetworkException failure) {
            throw ApiException.network(message(failure, "登录网络请求失败"));
        } catch (ResponseException failure) {
            throw ApiException.permissionDenied(message(failure, "用户名或密码错误"));
        }
        userInfo = result;
        saveCredentials(username, password);
        return result;
    }

    public SuccessResponse logout() {
        try {
            client.logout();
        } catch (NetworkException failure) {
            throw ApiException.network(message(failure, "退出登录网络请求失败"));
        } catch (ResponseException failure) {
            throw ApiException.permissionDenied(message(failure, "退出登录失败"));
        }
        userInfo = null;
        clearCredentials("退出后无法清除自动登录凭据");
        return SuccessResponse.ok();
    }

    public AutoLoginResponse autoLogin() {
        if (!credentials.isAvailable()) {
            throw ApiException.unavailable("操作系统安全凭据存储不可用，无法自动登录");
        }

        LoginCredentials saved;
        try {
            saved = credentials.load();
        } catch (RuntimeException failure) {
            throw ApiException.unavailable("无法读取操作系统安全凭据，自动登录不可用");
        }
        if (saved == null || saved.username() == null || saved.username().isBlank()
                || saved.password() == null || saved.password().isEmpty()) {
            throw ApiException.notFound("没有保存的自动登录凭据");
        }

        try {
            UserInfoResponse result = toUserInfoResponse(
                    client.login(saved.username(), saved.password()));
            userInfo = result;
            return new AutoLoginResponse(true, result);
        } catch (NetworkException failure) {
            userInfo = null;
            throw ApiException.network(message(failure, "自动登录网络请求失败"));
        } catch (ResponseException failure) {
            userInfo = null;
            clearCredentials("自动登录凭据已失效，但无法从安全存储清除");
            throw ApiException.permissionDenied("自动登录失败：凭据无效或已过期");
        }
    }

    public LoginStateResponse state() {
        UserInfoResponse currentUserInfo = userInfo;
        return currentUserInfo == null
                ? new LoginStateResponse(false, null, null)
                : new LoginStateResponse(true, currentUserInfo.username(), currentUserInfo);
    }

    public UserProfileResponse profile(String uid) {
        JmUserProfile profile = client.getUserProfile(uid);
        return new UserProfileResponse(
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
    }

    private void saveCredentials(String username, String password) {
        if (!credentials.isAvailable()) return;
        try {
            credentials.save(username, password);
        } catch (RuntimeException failure) {
            LOGGER.warn("登录成功，但无法保存自动登录凭据", failure);
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

    private static String message(RuntimeException failure, String fallback) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? fallback
                : failure.getMessage();
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
}
