package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.download.DownloadLocationService;
import io.github.jukomu.desktop.feature.download.model.DownloadLocationRequest;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import io.github.jukomu.desktop.feature.settings.model.*;
import io.javalin.http.Context;

import static io.github.jukomu.desktop.util.RequestValidation.requiredText;

/**
 * 处理页面基础设置的读取与持久化。
 */
public final class SettingsPluginHandler {
    private final RequestExecutor settingsRequests;
    private final RequestExecutor relocationRequests;
    private final SettingsService settings;
    private final DownloadLocationService downloadLocation;

    public SettingsPluginHandler(
        RequestExecutor settingsRequests,
        RequestExecutor relocationRequests,
        SettingsService settings,
        DownloadLocationService downloadLocation
    ) {
        this.settingsRequests = settingsRequests;
        this.relocationRequests = relocationRequests;
        this.settings = settings;
        this.downloadLocation = downloadLocation;
    }

    public void getAllSettings(Context context) {
        settingsRequests.run(context, settings::all);
    }

    public void setApiRoutePreference(Context context) {
        settingsRequests.run(context, ApiRouteRequest.class, request -> {
            settings.setApiRoute(request.mode(), request.domain());
            return SuccessResponse.ok();
        });
    }

    public void setPreloadConcurrency(Context context) {
        settingsRequests.run(context, NumberSettingRequest.class, request -> settings.setConcurrency(
            "preload_concurrency", Request.integer(request.n(), 6)));
    }

    public void setDownloadConcurrency(Context context) {
        settingsRequests.run(context, NumberSettingRequest.class, request -> settings.setConcurrency(
            "download_concurrency", Request.integer(request.n(), 6)));
    }

    public void setReaderPreloadPages(Context context) {
        settingsRequests.run(context, NumberSettingRequest.class, request -> settings.setReaderPreloadPages(
            Request.integer(request.n(), 15)));
    }

    public void setReaderDisplayMode(Context context) {
        settingsRequests.run(context, DisplayModeRequest.class, request -> settings.setDisplayMode(
            requiredText(request.mode(), "mode")));
    }

    public void setReaderWidthPercent(Context context) {
        settingsRequests.run(context, WidthPercentRequest.class, request -> settings.setReaderWidthPercent(request.value()));
    }

    public void setReaderAutoShowToolbarAtEnd(Context context) {
        settingsRequests.run(context, BooleanSettingRequest.class, request -> settings.setAutoShow(
            Request.bool(request.enabled(), true)));
    }

    public void getDownloadPublic(Context context) {
        settingsRequests.run(context, downloadLocation::get);
    }

    public void setDownloadPublic(Context context) {
        relocationRequests.runLongOperation(context, DownloadLocationRequest.class,
            request -> downloadLocation.set(Request.bool(request.open(), false)));
    }

    public void getExportPreferences(Context context) {
        settingsRequests.run(context, settings::exportPreferences);
    }

    public void setExportFolder(Context context) {
        settingsRequests.run(context, ExportFolderRequest.class,
            request -> settings.setExportFolder(request.folder()));
    }

    public void setExportDirectoryTemplate(Context context) {
        settingsRequests.run(context, NullableTextSettingRequest.class,
            request -> settings.setExportDirectoryTemplate(request.value()));
    }

    public void setExportFileNameTemplate(Context context) {
        settingsRequests.run(context, NullableTextSettingRequest.class,
            request -> settings.setExportFileNameTemplate(request.value()));
    }

    public void setExportLastFormat(Context context) {
        settingsRequests.run(context, NullableTextSettingRequest.class,
            request -> settings.setExportLastFormat(
                requiredText(request.value(), "value")));
    }
}
