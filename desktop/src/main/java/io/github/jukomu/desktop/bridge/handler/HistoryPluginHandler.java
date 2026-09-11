package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.service.DesktopHistoryService;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 负责浏览历史参数解析、事务调用和分页结果转换。 */
public final class HistoryPluginHandler {
    private final DesktopHistoryService historyService;
    private final Executor executor;

    public HistoryPluginHandler(DesktopHistoryService historyService, Executor executor) {
        this.historyService = Objects.requireNonNull(historyService, "historyService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void getBrowseHistory(Context context) {
        try {
            DesktopDtos.BrowseHistoryQuery request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.BrowseHistoryQuery.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> historyService.getBrowseHistory(
                            valueOrZero(request.limit()),
                            valueOrZero(request.offset()),
                            request.startInclusive(),
                            request.endExclusive()
                    )
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void getBrowseHistoryOverview(Context context) {
        try {
            DesktopDtos.BrowseHistoryOverviewRequest request = AsyncRequestHandler.readBody(
                    context,
                    DesktopDtos.BrowseHistoryOverviewRequest.class
            );
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> historyService.getBrowseHistoryOverview(request.ranges())
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void recordBrowse(Context context) {
        try {
            DesktopDtos.RecordBrowseRequest request = AsyncRequestHandler.readBody(
                    context,
                    DesktopDtos.RecordBrowseRequest.class
            );
            AsyncRequestHandler.submitJson(context, executor, () -> historyService.recordBrowse(request));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void clearBrowseHistory(Context context) {
        AsyncRequestHandler.submitJson(context, executor, historyService::clearBrowseHistory);
    }

    public void deleteBrowseItem(Context context) {
        try {
            DesktopDtos.NumberRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.NumberRequest.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> historyService.deleteBrowseItem(valueOrZero(request.n()))
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
