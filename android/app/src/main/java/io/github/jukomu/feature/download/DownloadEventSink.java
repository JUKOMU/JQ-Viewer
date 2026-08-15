package io.github.jukomu.feature.download;

import io.github.jukomu.feature.download.model.DownloadProgressData;

/**
 * 接收下载任务的状态和进度事件。
 */
public interface DownloadEventSink {
    void onDownloadProgress(DownloadProgressData data);
}
