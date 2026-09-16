package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.update.DesktopUpdateService;
import io.github.jukomu.desktop.feature.update.UpdateException;
import io.javalin.http.Context;

import java.util.function.Supplier;

/** Desktop 更新 bridge。 */
public final class UpdatePluginHandler {
    private final RequestExecutor requests;
    private final DesktopUpdateService updates;

    public UpdatePluginHandler(RequestExecutor requests, DesktopUpdateService updates) {
        this.requests = requests;
        this.updates = updates;
    }

    public void checkUpdate(Context context) {
        requests.run(context, () -> invoke(updates::check));
    }

    public void startUpdate(Context context) {
        requests.run(context, () -> invoke(updates::start));
    }

    public void cancelUpdate(Context context) {
        requests.run(context, () -> invoke(updates::cancel));
    }

    public void getUpdateState(Context context) {
        requests.run(context, updates::snapshot);
    }

    public void installUpdate(Context context) {
        requests.run(context, () -> invoke(updates::install));
    }

    private static <T> T invoke(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (UpdateException exception) {
            throw ApiException.unavailable(exception.getMessage());
        }
    }
}
