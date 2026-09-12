package io.github.jukomu.desktop.bridge.handler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.javalin.http.Context;

/** 提供服务状态查询。 */
public final class SystemPluginHandler {
    public void getInitStatus(Context context) {
        context.json(JsonNodeFactory.instance.objectNode().put("complete", true));
    }
}
