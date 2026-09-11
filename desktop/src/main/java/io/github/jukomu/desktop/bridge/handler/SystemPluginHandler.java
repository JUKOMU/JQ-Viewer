package io.github.jukomu.desktop.bridge.handler;

import io.javalin.http.Context;

/** 提供不依赖远程服务的系统方法。 */
public final class SystemPluginHandler {
    public void getInitStatus(Context context) {
        context.json(new InitStatus(true));
    }

    public record InitStatus(boolean complete) {
    }
}
