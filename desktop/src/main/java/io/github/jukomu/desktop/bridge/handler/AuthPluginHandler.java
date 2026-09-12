package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.auth.AuthService;
import io.github.jukomu.desktop.feature.auth.model.LoginRequest;
import io.github.jukomu.desktop.feature.auth.model.UserProfileRequest;
import io.javalin.http.Context;

/** 处理当前进程登录会话的 bridge 请求。 */
public final class AuthPluginHandler {
    private final RequestExecutor requests;
    private final AuthService auth;

    public AuthPluginHandler(RequestExecutor requests, AuthService auth) {
        this.requests = requests;
        this.auth = auth;
    }

    public void login(Context context) {
        requests.run(context, LoginRequest.class, request -> auth.login(
                Request.requiredText(request.username(), "username"),
                Request.requiredText(request.password(), "password")));
    }

    public void logout(Context context) {
        requests.run(context, auth::logout);
    }

    public void checkLoginState(Context context) {
        requests.run(context, auth::state);
    }

    public void getUserProfile(Context context) {
        requests.run(context, UserProfileRequest.class,
                request -> auth.profile(Request.requiredText(request.uid(), "uid")));
    }
}
