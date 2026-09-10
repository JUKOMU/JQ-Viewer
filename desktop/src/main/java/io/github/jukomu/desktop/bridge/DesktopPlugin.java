package io.github.jukomu.desktop.bridge;

import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.javalin.http.Context;

/** The single Desktop bridge exposed to the local HTTP backend. */
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
