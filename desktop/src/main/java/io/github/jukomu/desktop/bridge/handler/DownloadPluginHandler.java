package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.download.DownloadService;
import io.github.jukomu.desktop.feature.download.model.*;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

import static io.github.jukomu.desktop.util.RequestValidation.requiredText;

/**
 * 处理下载任务 bridge 请求并完成参数适配。
 */
public final class DownloadPluginHandler {
    private final RequestExecutor requests;
    private final DownloadService downloads;

    public DownloadPluginHandler(RequestExecutor requests, DownloadService downloads) {
        this.requests = requests;
        this.downloads = downloads;
    }

    public void downloadChapter(Context context) {
        requests.runLongOperation(context, DownloadChapterRequest.class, downloads::downloadChapter);
    }

    public void getDownloadTasksPage(Context context) {
        requests.runLongOperation(context, DownloadTasksPageRequest.class,
            downloads::getDownloadTasksPage);
    }

    public void getCompletedDownloadAlbumIds(Context context) {
        requests.run(context, AlbumIdsRequest.class,
            request -> Map.of("albumIds", downloads.getCompletedDownloadAlbumIds(request.albumIds())));
    }

    public void getDownloadTaskStatuses(Context context) {
        requests.run(context, DownloadedChapterRequest.class, request -> Map.of(
            "statuses", downloads.getDownloadTaskStatuses(requiredText(request.albumId(), "albumId"))
                .stream().map(task -> Map.of("chapterId", task.chapterId(), "status", task.status())).toList()));
    }

    public void getCompletedDownloadChapters(Context context) {
        requests.run(context, DownloadedChapterRequest.class, request -> Map.of(
            "tasks", downloads.getCompletedDownloadChapters(requiredText(request.albumId(), "albumId"))));
    }

    public void getActiveDownloadProgress(Context context) {
        requests.runLongOperation(context, () -> Map.of(
            "tasks", downloads.getActiveDownloadProgress()));
    }

    public void getDownloadedResourceKeys(Context context) {
        requests.run(context, DownloadResourceKeysRequest.class, request -> Map.of(
            "keys", downloads.getDownloadedResourceKeys(request.items())));
    }

    public void clearDownloadTasks(Context context) {
        requests.runLongOperation(context, ClearDownloadTasksRequest.class, request -> {
            Map<String, Integer> result = downloads.clearDownloadTasks(
                requiredText(request.status(), "status"));
            return Map.of("success", true, "deletedCount", result.get("deletedCount"),
                "failedCount", result.get("failedCount"));
        });
    }

    public void cancelDownload(Context context) {
        requests.runLongOperation(context, DownloadTaskIdRequest.class, request -> {
            downloads.cancelDownload(requiredText(request.taskId(), "taskId"));
            return SuccessResponse.ok();
        });
    }

    public void pauseDownload(Context context) {
        requests.run(context, DownloadTaskIdRequest.class, request -> {
            downloads.pauseDownload(requiredText(request.taskId(), "taskId"));
            return SuccessResponse.ok();
        });
    }

    public void resumeDownload(Context context) {
        requests.run(context, DownloadTaskIdRequest.class, request -> {
            downloads.resumeDownload(requiredText(request.taskId(), "taskId"));
            return SuccessResponse.ok();
        });
    }

    public void deleteDownloaded(Context context) {
        requests.runLongOperation(context, DownloadedChapterRequest.class, request -> {
            downloads.deleteDownloaded(
                requiredText(request.albumId(), "albumId"),
                requiredText(request.chapterId(), "chapterId"));
            return SuccessResponse.ok();
        });
    }

    public void getDownloadedPhoto(Context context) {
        requests.runLongOperation(context, DownloadedChapterRequest.class,
            request -> downloads.getDownloadedPhoto(
                requiredText(request.albumId(), "albumId"),
                requiredText(request.chapterId(), "chapterId")));
    }

    public record AlbumIdsRequest(List<String> albumIds) {
    }
}
