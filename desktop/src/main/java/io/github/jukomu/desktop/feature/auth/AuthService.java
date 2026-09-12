package io.github.jukomu.desktop.feature.auth;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import io.github.jukomu.jmcomic.api.model.JmUserProfile;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;

/** 管理当前 backend 进程内的登录会话。 */
public final class AuthService {
    private final JmApiClient client;
    private volatile ObjectNode userInfo;

    public AuthService(JmApiClient client) {
        this.client = client;
    }

    public ObjectNode login(String username, String password) {
        ObjectNode result = toUserInfo(client.login(username, password));
        userInfo = result;
        return result.deepCopy();
    }

    public ObjectNode logout() {
        client.logout();
        userInfo = null;
        return JsonNodeFactory.instance.objectNode().put("success", true);
    }

    public ObjectNode state() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("loggedIn", userInfo != null);
        if (userInfo != null) {
            result.put("username", userInfo.path("username").asText());
            result.set("userInfo", userInfo.deepCopy());
        }
        return result;
    }

    public ObjectNode profile(String uid) {
        JmUserProfile profile = client.getUserProfile(uid);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("username", text(profile.username()));
        result.put("email", text(profile.email()));
        result.put("nickname", text(profile.nickname()));
        result.put("birthday", text(profile.birthday()));
        result.put("city", text(profile.city()));
        result.put("country", text(profile.country()));
        result.put("occupation", text(profile.occupation()));
        result.put("aboutMe", text(profile.aboutMe()));
        result.put("website", text(profile.website()));
        return result;
    }

    private static ObjectNode toUserInfo(JmUserInfo info) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("uid", text(info.getUid()));
        result.put("username", text(info.getUsername()));
        result.put("email", text(info.getEmail()));
        result.put("emailVerified", info.isEmailVerified());
        result.put("avatarUrl", text(info.getPhotoUrl()));
        result.put("firstName", text(info.getFirstName()));
        result.put("gender", text(info.getGender()));
        result.put("message", text(info.getMessage()));
        result.put("level", info.getLevel());
        result.put("levelName", text(info.getLevelName()));
        result.put("nextLevelExp", info.getNextLevelExp());
        result.put("currentExp", info.getCurrentExp());
        result.put("expPercent", info.getExpPercent());
        result.put("coin", info.getCoin());
        result.put("albumFavorites", info.getAlbumFavorites());
        result.put("maxAlbumFavorites", info.getMaxAlbumFavorites());
        return result;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
