package io.github.jukomu.desktop.feature.auth;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;

import java.util.Objects;

/** 使用当前进程的 JmApiClient 管理不落盘的 Desktop 登录态。 */
public final class JmComicAuthService {
    private final JmApiClient client;
    private ObjectNode currentUser;

    public JmComicAuthService(JmApiClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public synchronized ObjectNode login(String username, String password) {
        ObjectNode user = AuthJson.userInfo(client.login(
                requireText(username, "username"),
                requireText(password, "password")
        ));
        currentUser = user.deepCopy();
        return user;
    }

    public synchronized ObjectNode logout() {
        if (currentUser != null) client.logout();
        currentUser = null;
        return JsonNodeFactory.instance.objectNode().put("success", true);
    }

    public synchronized ObjectNode checkLoginState() {
        ObjectNode result = JsonNodeFactory.instance.objectNode()
                .put("loggedIn", currentUser != null);
        if (currentUser != null) {
            result.put("username", currentUser.path("username").asText(""));
            result.set("userInfo", currentUser.deepCopy());
        }
        return result;
    }

    public ObjectNode getUserProfile(String uid) {
        return AuthJson.profile(client.getUserProfile(requireText(uid, "uid")));
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
