package io.github.jukomu.bridge;

import com.getcapacitor.JSObject;

import io.github.jukomu.feature.download.DownloadEventSink;
import io.github.jukomu.feature.download.model.DownloadProgressData;
import io.github.jukomu.feature.preload.PreloadEventSink;
import io.github.jukomu.feature.settings.relocation.RelocationEventSink;

import java.util.function.BiConsumer;

/**
 * 将下载、预加载和目录搬迁事件转换为 Capacitor 事件数据。
 */
final class FeatureEventAdapter implements DownloadEventSink, PreloadEventSink,
    RelocationEventSink {

    private final BiConsumer<String, JSObject> eventPublisher;

    FeatureEventAdapter(BiConsumer<String, JSObject> eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onDownloadProgress(DownloadProgressData data) {
        JSObject event = new JSObject();
        event.put("taskId", data.taskId);
        event.put("albumId", data.albumId);
        event.put("chapterId", data.chapterId);
        event.put("downloadedPages", data.downloadedPages);
        event.put("totalPages", data.totalPages);
        event.put("status", data.status);
        if (data.error != null) {
            event.put("error", data.error);
        }
        event.put("speed", data.speed);
        if (data.downloadedBytes > 0) {
            event.put("downloadedBytes", data.downloadedBytes);
        }
        if (data.totalSize > 0) {
            event.put("totalSize", data.totalSize);
        }
        eventPublisher.accept("downloadProgress", event);
    }

    @Override
    public void onImageReady(String photoId, int sortOrder, String type) {
        JSObject event = new JSObject();
        event.put("photoId", photoId);
        event.put("sortOrder", sortOrder);
        event.put("type", type);
        eventPublisher.accept("imageReady", event);
    }

    @Override
    public void onImageFailed(String photoId, int sortOrder, String type) {
        JSObject event = new JSObject();
        event.put("photoId", photoId);
        event.put("sortOrder", sortOrder);
        event.put("type", type);
        eventPublisher.accept("imageFailed", event);
    }

    @Override
    public void onRelocationProgress(int current, int total, String phase,
                                     String currentFile) {
        JSObject event = new JSObject();
        event.put("current", current);
        event.put("total", total);
        event.put("phase", phase);
        if (currentFile != null) {
            event.put("currentFile", currentFile);
        }
        eventPublisher.accept("relocationProgress", event);
    }
}
