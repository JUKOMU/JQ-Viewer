package io.github.jukomu.desktop.feature.auth;

/**
 * 从安全存储读取的登录凭据。
 */
public record LoginCredentials(String username, String password) {
}
