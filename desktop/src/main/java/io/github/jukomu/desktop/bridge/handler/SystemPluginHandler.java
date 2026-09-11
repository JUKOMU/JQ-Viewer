package io.github.jukomu.desktop.bridge.handler;

import io.javalin.http.Context;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** 提供初始化状态等不依赖请求参数的系统方法。 */
public final class SystemPluginHandler {
    private final BooleanSupplier clientPresent;

    public SystemPluginHandler() {
        this(() -> true);
    }

    public SystemPluginHandler(BooleanSupplier clientPresent) {
        this.clientPresent = Objects.requireNonNull(clientPresent, "clientPresent");
    }

    public void getInitStatus(Context context) {
        context.json(new InitStatus(clientPresent.getAsBoolean()));
    }

    public record InitStatus(boolean complete) {
    }
}
