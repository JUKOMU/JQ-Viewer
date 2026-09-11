package io.github.jukomu.desktop.feature.auth;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import io.github.jukomu.jmcomic.api.model.JmUserProfile;

/** 将上游认证模型转换为前端既有的认证 JSON 契约。 */
public final class AuthJson {
    private AuthJson() {
    }

    public static ObjectNode userInfo(JmUserInfo user) {
        return JsonNodeFactory.instance.objectNode()
                .put("uid", text(user.getUid()))
                .put("username", text(user.getUsername()))
                .put("email", text(user.getEmail()))
                .put("emailVerified", user.isEmailVerified())
                .put("avatarUrl", text(user.getPhotoUrl()))
                .put("firstName", text(user.getFirstName()))
                .put("gender", text(user.getGender()))
                .put("message", text(user.getMessage()))
                .put("level", user.getLevel())
                .put("levelName", text(user.getLevelName()))
                .put("nextLevelExp", user.getNextLevelExp())
                .put("currentExp", user.getCurrentExp())
                .put("expPercent", user.getExpPercent())
                .put("coin", user.getCoin())
                .put("albumFavorites", user.getAlbumFavorites())
                .put("maxAlbumFavorites", user.getMaxAlbumFavorites());
    }

    public static ObjectNode profile(JmUserProfile profile) {
        return JsonNodeFactory.instance.objectNode()
                .put("username", text(profile.username()))
                .put("email", text(profile.email()))
                .put("nickname", text(profile.nickname()))
                .put("birthday", text(profile.birthday()))
                .put("city", text(profile.city()))
                .put("country", text(profile.country()))
                .put("occupation", text(profile.occupation()))
                .put("aboutMe", text(profile.aboutMe()))
                .put("website", text(profile.website()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
