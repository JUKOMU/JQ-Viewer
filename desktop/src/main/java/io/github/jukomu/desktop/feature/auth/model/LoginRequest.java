package io.github.jukomu.desktop.feature.auth.model;

/**
 * 承载登录参数；rememberMe 未提供时由服务按旧版行为视为 true。
 */
public record LoginRequest(String username, String password, Boolean rememberMe) {
}
