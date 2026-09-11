package io.github.jukomu.desktop;

import io.github.jukomu.desktop.bridge.PluginMethod;
import io.javalin.http.Context;

import java.util.Map;

/** 阶段 1 生命周期测试用的最小 bridge，不创建真实上游客户端。 */
final class InitOnlyPlugin {
    @PluginMethod
    public void getInitStatus(Context context) {
        context.json(Map.of("complete", true));
    }
}
