package io.github.jukomu.desktop.service;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;

import java.util.Objects;

/** 使用单个 JmApiClient 持有浏览器刷新期间仍有效的进程内登录态。 */
public final class JmComicAuthService implements DesktopAuthService {
    private final JmApiClient client;
    private DesktopDtos.UserInfo currentUser;

    public JmComicAuthService(JmApiClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public synchronized DesktopDtos.UserInfo login(String username, String password) {
        String normalizedUsername = requireText(username, "username");
        String normalizedPassword = requireText(password, "password");
        DesktopDtos.UserInfo result = DesktopDtoMapper.userInfo(
                client.login(normalizedUsername, normalizedPassword)
        );
        currentUser = result;
        return result;
    }

    @Override
    public synchronized DesktopDtos.Success logout() {
        if (currentUser != null) {
            client.logout();
            currentUser = null;
        }
        return new DesktopDtos.Success(true);
    }

    @Override
    public synchronized DesktopDtos.LoginState checkLoginState() {
        if (currentUser == null) {
            return new DesktopDtos.LoginState(false, null, null);
        }
        return new DesktopDtos.LoginState(true, currentUser.username(), currentUser);
    }

    @Override
    public synchronized DesktopDtos.UserProfile getUserProfile(String uid) {
        return DesktopDtoMapper.userProfile(client.getUserProfile(requireText(uid, "uid")));
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }
}
