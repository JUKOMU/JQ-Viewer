package io.github.jukomu.runtime;

import io.github.jukomu.feature.download.DownloadEventSink;
import io.github.jukomu.feature.download.model.DownloadProgressData;
import io.github.jukomu.feature.preload.PreloadEventSink;
import io.github.jukomu.feature.settings.relocation.RelocationEventSink;

/**
 * 进程级服务的可重新绑定事件出口。
 */
final class RuntimeEventRouter implements DownloadEventSink, PreloadEventSink,
    RelocationEventSink {

    private volatile DownloadEventSink downloadDelegate;
    private volatile PreloadEventSink preloadDelegate;
    private volatile RelocationEventSink relocationDelegate;

    synchronized void attach(DownloadEventSink downloadSink,
                             PreloadEventSink preloadSink,
                             RelocationEventSink relocationSink) {
        downloadDelegate = downloadSink;
        preloadDelegate = preloadSink;
        relocationDelegate = relocationSink;
    }

    synchronized void detach(DownloadEventSink expectedDownload,
                             PreloadEventSink expectedPreload,
                             RelocationEventSink expectedRelocation) {
        if (downloadDelegate == expectedDownload) {
            downloadDelegate = null;
        }
        if (preloadDelegate == expectedPreload) {
            preloadDelegate = null;
        }
        if (relocationDelegate == expectedRelocation) {
            relocationDelegate = null;
        }
    }

    @Override
    public void onDownloadProgress(DownloadProgressData data) {
        DownloadEventSink current = downloadDelegate;
        if (current != null) {
            current.onDownloadProgress(data);
        }
    }

    @Override
    public void onImageReady(String photoId, int sortOrder, String type) {
        PreloadEventSink current = preloadDelegate;
        if (current != null) {
            current.onImageReady(photoId, sortOrder, type);
        }
    }

    @Override
    public void onImageFailed(String photoId, int sortOrder, String type) {
        PreloadEventSink current = preloadDelegate;
        if (current != null) {
            current.onImageFailed(photoId, sortOrder, type);
        }
    }

    @Override
    public void onRelocationProgress(int current, int total, String phase,
                                     String currentFile) {
        RelocationEventSink sink = relocationDelegate;
        if (sink != null) {
            sink.onRelocationProgress(current, total, phase, currentFile);
        }
    }
}
