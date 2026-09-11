package io.github.jukomu.desktop.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.feature.history.DesktopHistory;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 解析浏览历史请求，并在业务线程池完成 SQLite 访问。 */
public final class HistoryPluginHandler {
    private final DesktopHistory history;
    private final Executor executor;

    public HistoryPluginHandler(DesktopHistory history, Executor executor) {
        this.history = Objects.requireNonNull(history, "history");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void getBrowseHistory(Context context) {
        try {
            ObjectNode body = RequestJson.body(context);
            int limit = RequestJson.integer(body, "limit", 0);
            int offset = RequestJson.integer(body, "offset", 0);
            Long start = RequestJson.nullableLong(body, "startInclusive");
            Long end = RequestJson.nullableLong(body, "endExclusive");
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> history.getBrowseHistory(limit, offset, start, end)
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void getBrowseHistoryOverview(Context context) {
        try {
            JsonNode ranges = RequestJson.body(context).get("ranges");
            if (!(ranges instanceof ArrayNode array)) {
                throw new IllegalArgumentException("ranges is required");
            }
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> history.getBrowseHistoryOverview(array)
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void recordBrowse(Context context) {
        try {
            ObjectNode body = RequestJson.body(context);
            AsyncRequestHandler.submitJson(context, executor, () -> history.recordBrowse(body));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void clearBrowseHistory(Context context) {
        AsyncRequestHandler.submitJson(context, executor, history::clearBrowseHistory);
    }

    public void deleteBrowseItem(Context context) {
        try {
            long id = RequestJson.longValue(RequestJson.body(context), "id", 0);
            AsyncRequestHandler.submitJson(context, executor, () -> history.deleteBrowseItem(id));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }
}
