package io.github.jukomu.desktop.feature.auth.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 返回当前进程内的登录状态；安全凭据读取失败时 hasSavedCredentials 为 null。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginStateResponse(
    boolean loggedIn,
    String username,
    UserInfoResponse userInfo,
    Boolean hasSavedCredentials
) {
}
