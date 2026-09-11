package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.service.DesktopSettingsService;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 负责基础设置的请求解析、校验和 SQLite 持久化调度。 */
public final class SettingsPluginHandler {
    private final DesktopSettingsService settingsService;
    private final Executor executor;

    public SettingsPluginHandler(DesktopSettingsService settingsService, Executor executor) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void getAllSettings(Context context) {
        AsyncRequestHandler.submitJson(context, executor, settingsService::getAllSettings);
    }

    public void setPreloadConcurrency(Context context) {
        setNumber(context, settingsService::setPreloadConcurrency);
    }

    public void setDownloadConcurrency(Context context) {
        setNumber(context, settingsService::setDownloadConcurrency);
    }

    public void setReaderPreloadPages(Context context) {
        setNumber(context, settingsService::setReaderPreloadPages);
    }

    public void setReaderDisplayMode(Context context) {
        try {
            DesktopDtos.StringRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.StringRequest.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> {
                        settingsService.setReaderDisplayMode(request.mode());
                        return new DesktopDtos.Success(true);
                    }
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void setReaderAutoShowToolbarAtEnd(Context context) {
        try {
            DesktopDtos.BooleanRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.BooleanRequest.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> {
                        settingsService.setReaderAutoShowToolbarAtEnd(request.enabled());
                        return new DesktopDtos.Success(true);
                    }
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    private void setNumber(Context context, NumberSetter setter) {
        try {
            DesktopDtos.NumberRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.NumberRequest.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> {
                        setter.set(request.n());
                        return new DesktopDtos.Success(true);
                    }
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    @FunctionalInterface
    private interface NumberSetter {
        void set(Integer value) throws Exception;
    }
}
