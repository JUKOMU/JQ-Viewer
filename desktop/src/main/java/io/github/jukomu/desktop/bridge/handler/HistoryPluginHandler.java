package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.history.HistoryService;
import io.github.jukomu.desktop.feature.history.model.HistoryDeleteRequest;
import io.github.jukomu.desktop.feature.history.model.HistoryOverviewRequest;
import io.github.jukomu.desktop.feature.history.model.HistoryPageRequest;
import io.github.jukomu.desktop.feature.history.model.HistoryRecordRequest;
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
        requests.run(context, HistoryPageRequest.class, request -> history.page(
                Request.integer(request.limit(), 0),
                Request.integer(request.offset(), 0),
                request.startInclusive(),
                request.endExclusive()));
    }

    public void getBrowseHistoryOverview(Context context) {
        requests.run(context, HistoryOverviewRequest.class,
                request -> history.overview(request.ranges()));
    }

    public void recordBrowse(Context context) {
        requests.run(context, HistoryRecordRequest.class, history::record);
    }

    public void clearBrowseHistory(Context context) {
        requests.run(context, history::clear);
    }

    public void deleteBrowseItem(Context context) {
        requests.run(context, HistoryDeleteRequest.class,
                request -> history.delete(Request.longValue(request.id(), 0)));
    }
}
