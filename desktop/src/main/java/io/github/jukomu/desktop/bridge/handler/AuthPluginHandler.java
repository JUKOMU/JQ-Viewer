package io.github.jukomu.desktop.bridge.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.feature.auth.JmComicAuthService;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 解析认证请求，并维护当前 Desktop 进程的登录态。 */
public final class AuthPluginHandler {
    private final JmComicAuthService auth;
    private final Executor executor;

    public AuthPluginHandler(JmComicAuthService auth, Executor executor) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void login(Context context) {
        try {
            ObjectNode body = RequestJson.body(context);
            String username = RequestJson.requiredText(body, "username");
            String password = RequestJson.requiredText(body, "password");
            AsyncRequestHandler.submitJson(context, executor, () -> auth.login(username, password));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void logout(Context context) {
        AsyncRequestHandler.submitJson(context, executor, auth::logout);
    }

    public void checkLoginState(Context context) {
        AsyncRequestHandler.submitJson(context, executor, auth::checkLoginState);
    }

    public void getUserProfile(Context context) {
        try {
            String uid = RequestJson.requiredText(RequestJson.body(context), "uid");
            AsyncRequestHandler.submitJson(context, executor, () -> auth.getUserProfile(uid));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }
}
