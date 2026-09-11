package io.github.jukomu.desktop.bridge.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.feature.settings.DesktopSettings;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 解析 Desktop 设置请求，并在业务线程池完成 SQLite 访问。 */
public final class SettingsPluginHandler {
    private final DesktopSettings settings;
    private final Executor executor;

    public SettingsPluginHandler(DesktopSettings settings, Executor executor) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void getAllSettings(Context context) {
        AsyncRequestHandler.submitJson(context, executor, settings::getAllSettings);
    }

    public void setPreloadConcurrency(Context context) {
        number(context, settings::setPreloadConcurrency);
    }

    public void setDownloadConcurrency(Context context) {
        number(context, settings::setDownloadConcurrency);
    }

    public void setReaderPreloadPages(Context context) {
        number(context, settings::setReaderPreloadPages);
    }

    public void setReaderDisplayMode(Context context) {
        try {
            String mode = RequestJson.requiredText(RequestJson.body(context), "mode");
            AsyncRequestHandler.submitJson(context, executor, () -> settings.setReaderDisplayMode(mode));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void setReaderAutoShowToolbarAtEnd(Context context) {
        try {
            boolean enabled = RequestJson.requiredBoolean(RequestJson.body(context), "enabled");
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> settings.setReaderAutoShowToolbarAtEnd(enabled)
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    private void number(Context context, NumberOperation operation) {
        try {
            int value = RequestJson.integer(RequestJson.body(context), "n", Integer.MIN_VALUE);
            AsyncRequestHandler.submitJson(context, executor, () -> operation.call(value));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    @FunctionalInterface
    private interface NumberOperation {
        ObjectNode call(int value) throws Exception;
    }
}
