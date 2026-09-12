package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import io.github.jukomu.desktop.feature.settings.model.BooleanSettingRequest;
import io.github.jukomu.desktop.feature.settings.model.DisplayModeRequest;
import io.github.jukomu.desktop.feature.settings.model.NumberSettingRequest;
import io.javalin.http.Context;

/** 处理页面基础设置的读取与持久化。 */
public final class SettingsPluginHandler {
    private final RequestExecutor requests;
    private final SettingsService settings;

    public SettingsPluginHandler(RequestExecutor requests, SettingsService settings) {
        this.requests = requests;
        this.settings = settings;
    }

    public void getAllSettings(Context context) {
        requests.run(context, settings::all);
    }

    public void setPreloadConcurrency(Context context) {
        requests.run(context, NumberSettingRequest.class, request -> settings.setConcurrency(
                "preload_concurrency", Request.integer(request.n(), 6)));
    }

    public void setDownloadConcurrency(Context context) {
        requests.run(context, NumberSettingRequest.class, request -> settings.setConcurrency(
                "download_concurrency", Request.integer(request.n(), 6)));
    }

    public void setReaderPreloadPages(Context context) {
        requests.run(context, NumberSettingRequest.class, request -> settings.setReaderPreloadPages(
                Request.integer(request.n(), 15)));
    }

    public void setReaderDisplayMode(Context context) {
        requests.run(context, DisplayModeRequest.class, request -> settings.setDisplayMode(
                Request.requiredText(request.mode(), "mode")));
    }

    public void setReaderAutoShowToolbarAtEnd(Context context) {
        requests.run(context, BooleanSettingRequest.class, request -> settings.setAutoShow(
                Request.bool(request.enabled(), true)));
    }
}
