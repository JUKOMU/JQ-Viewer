package io.github.jukomu.feature.settings.relocation;

/**
 * 接收下载目录搬迁进度事件。
 */
public interface RelocationEventSink {
    void onRelocationProgress(int current, int total, String phase, String currentFile);
}
