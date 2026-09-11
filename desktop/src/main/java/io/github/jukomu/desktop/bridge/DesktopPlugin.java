package io.github.jukomu.desktop.bridge;

import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.javalin.http.Context;

/** 向本地 HTTP backend 暴露的唯一 Desktop bridge。 */
public final class DesktopPlugin {
    private final SystemPluginHandler systemHandler;

    public DesktopPlugin() {
        this(new SystemPluginHandler());
    }

    public DesktopPlugin(SystemPluginHandler systemHandler) {
        this.systemHandler = systemHandler;
    }

    @PluginMethod
    public void getInitStatus(Context context) {
        systemHandler.getInitStatus(context);
    }
}
