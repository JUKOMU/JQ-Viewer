package io.github.jukomu.desktop.feature.auth.model;

/**
 * 返回自动登录结果和恢复的用户信息。
 */
public record AutoLoginResponse(boolean success, UserInfoResponse userInfo) {
}
