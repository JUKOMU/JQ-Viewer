package io.github.jukomu.desktop.bridge.handler;

import io.javalin.http.Context;

/** Stage 1 system methods which do not require a remote service. */
public final class SystemPluginHandler {
    public void getInitStatus(Context context) {
        context.json(new InitStatus(true));
    }

    public record InitStatus(boolean complete) {
    }
}
