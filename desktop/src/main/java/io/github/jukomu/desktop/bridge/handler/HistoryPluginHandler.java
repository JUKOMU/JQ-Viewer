package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.history.HistoryService;
import io.javalin.http.Context;

/** 处理浏览历史的 JSON bridge 请求。 */
public final class HistoryPluginHandler {
    private final RequestExecutor requests;
    private final HistoryService history;

    public HistoryPluginHandler(RequestExecutor requests, HistoryService history) {
        this.requests = requests;
        this.history = history;
    }

    public void getBrowseHistory(Context context) {
        requests.run(context, request -> history.page(
                Request.integer(request, "limit", 0), Request.integer(request, "offset", 0),
                optional(request, "startInclusive"), optional(request, "endExclusive")));
    }

    public void getBrowseHistoryOverview(Context context) {
        requests.run(context, request -> history.overview(Request.array(request, "ranges")));
    }

    public void recordBrowse(Context context) {
        requests.run(context, history::record);
    }

    public void clearBrowseHistory(Context context) {
        requests.run(context, ignored -> history.clear());
    }

    public void deleteBrowseItem(Context context) {
        requests.run(context, request -> history.delete(Request.longValue(request, "id", 0)));
    }

    private static Long optional(com.fasterxml.jackson.databind.node.ObjectNode request, String name) {
        if (!request.has(name) || request.get(name).isNull()) return null;
        return Request.longValue(request, name, 0);
    }
}
