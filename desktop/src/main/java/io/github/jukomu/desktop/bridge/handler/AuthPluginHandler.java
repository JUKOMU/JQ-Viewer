package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.service.DesktopAuthService;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 负责 Desktop 登录、登出、登录态和用户资料请求。 */
public final class AuthPluginHandler {
    private final DesktopAuthService authService;
    private final Executor executor;

    public AuthPluginHandler(DesktopAuthService authService, Executor executor) {
        this.authService = Objects.requireNonNull(authService, "authService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void login(Context context) {
        try {
            DesktopDtos.LoginRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.LoginRequest.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> authService.login(request.username(), request.password())
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void logout(Context context) {
        AsyncRequestHandler.submitJson(context, executor, authService::logout);
    }

    public void checkLoginState(Context context) {
        AsyncRequestHandler.submitJson(context, executor, authService::checkLoginState);
    }

    public void getUserProfile(Context context) {
        try {
            DesktopDtos.IdRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.IdRequest.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> authService.getUserProfile(request.id())
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }
}
