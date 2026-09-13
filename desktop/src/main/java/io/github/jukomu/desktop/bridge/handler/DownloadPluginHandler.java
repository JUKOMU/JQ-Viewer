package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.download.DownloadService;
import io.github.jukomu.desktop.feature.download.model.DownloadChapterRequest;
import io.github.jukomu.desktop.feature.download.model.DownloadTaskIdRequest;
import io.github.jukomu.desktop.feature.download.model.DownloadedChapterRequest;
import io.javalin.http.Context;

/** 处理下载任务 bridge 请求并完成参数适配。 */
public final class DownloadPluginHandler {
    private final RequestExecutor requests;
    private final DownloadService downloads;

    public DownloadPluginHandler(RequestExecutor requests, DownloadService downloads) {
        this.requests = requests;
        this.downloads = downloads;
    }

    public void downloadChapter(Context context) {
        requests.run(context, DownloadChapterRequest.class, downloads::downloadChapter);
    }

    public void getDownloadTasks(Context context) {
        requests.run(context, downloads::getDownloadTasks);
    }

    public void cancelDownload(Context context) {
        requests.run(context, DownloadTaskIdRequest.class, request -> {
            downloads.cancelDownload(Request.requiredText(request.taskId(), "taskId"));
            return SuccessResponse.ok();
        });
    }

    public void pauseDownload(Context context) {
        requests.run(context, DownloadTaskIdRequest.class, request -> {
            downloads.pauseDownload(Request.requiredText(request.taskId(), "taskId"));
            return SuccessResponse.ok();
        });
    }

    public void resumeDownload(Context context) {
        requests.run(context, DownloadTaskIdRequest.class, request -> {
            downloads.resumeDownload(Request.requiredText(request.taskId(), "taskId"));
            return SuccessResponse.ok();
        });
    }

    public void deleteDownloaded(Context context) {
        requests.run(context, DownloadedChapterRequest.class, request -> {
            downloads.deleteDownloaded(
                    Request.requiredText(request.albumId(), "albumId"),
                    Request.requiredText(request.chapterId(), "chapterId"));
            return SuccessResponse.ok();
        });
    }

    public void getDownloadedPhoto(Context context) {
        requests.run(context, DownloadedChapterRequest.class, request -> downloads.getDownloadedPhoto(
                Request.requiredText(request.albumId(), "albumId"),
                Request.requiredText(request.chapterId(), "chapterId")));
    }
}
