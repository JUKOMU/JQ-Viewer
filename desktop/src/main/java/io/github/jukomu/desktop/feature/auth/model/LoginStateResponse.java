package io.github.jukomu.desktop.feature.auth.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 返回当前进程内的登录状态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginStateResponse(
        boolean loggedIn,
        String username,
        UserInfoResponse userInfo
) {
}
