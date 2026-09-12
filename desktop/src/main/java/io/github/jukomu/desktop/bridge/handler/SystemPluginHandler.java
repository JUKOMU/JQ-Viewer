package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.model.InitStatusResponse;
import io.javalin.http.Context;

/** 提供服务状态查询。 */
public final class SystemPluginHandler {
    public void getInitStatus(Context context) {
        context.json(new InitStatusResponse(true));
    }
}
