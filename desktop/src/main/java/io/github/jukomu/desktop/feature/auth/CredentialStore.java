package io.github.jukomu.desktop.feature.auth;

/**
 * 保存自动登录凭据的操作系统安全存储边界。
 */
public interface CredentialStore {
    boolean isAvailable();

    LoginCredentials load();

    void save(String username, String password);

    void clear();
}
