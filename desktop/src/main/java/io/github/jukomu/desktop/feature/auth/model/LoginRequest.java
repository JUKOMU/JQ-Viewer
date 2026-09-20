package io.github.jukomu.desktop.feature.auth.model;

/** 承载用户名和密码登录参数。 */
public record LoginRequest(String username, String password) {
}
