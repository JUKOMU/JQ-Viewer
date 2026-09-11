package io.github.jukomu.desktop.backend;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.jukomu.desktop.bridge.PluginMethod;
import io.javalin.http.Context;

public final class InitOnlyPlugin {
    @PluginMethod
    public void getInitStatus(Context context) {
        context.json(JsonNodeFactory.instance.objectNode().put("complete", true));
    }
}
