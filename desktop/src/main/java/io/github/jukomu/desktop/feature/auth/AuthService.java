package io.github.jukomu.desktop.feature.auth;

import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.auth.model.LoginStateResponse;
import io.github.jukomu.desktop.feature.auth.model.UserInfoResponse;
import io.github.jukomu.desktop.feature.auth.model.UserProfileResponse;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import io.github.jukomu.jmcomic.api.model.JmUserProfile;

/** 管理当前 backend 进程内的登录会话。 */
public final class AuthService {
    private final JmClient client;
    private volatile UserInfoResponse userInfo;

    public AuthService(JmClient client) {
        this.client = client;
    }

    public UserInfoResponse login(String username, String password) {
        UserInfoResponse result = toUserInfoResponse(client.login(username, password));
        userInfo = result;
        return result;
    }

    public SuccessResponse logout() {
        client.logout();
        userInfo = null;
        return SuccessResponse.ok();
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
