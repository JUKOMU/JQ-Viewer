package io.github.jukomu.feature.preload;

/**
 * 接收图片预加载完成事件。
 */
public interface PreloadEventSink {
    void onImageReady(String photoId, int sortOrder, String type);

    void onImageFailed(String photoId, int sortOrder, String type);
}
