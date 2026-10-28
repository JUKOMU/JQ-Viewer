package io.github.jukomu.feature.preload;

/**
 * 接收图片预加载完成事件。
 */
public interface PreloadEventSink {
    default void onImageReady(String photoId, int sortOrder, String type) {
    }

    default void onImageReady(String photoId, int sortOrder, String type, String sessionId) {
        onImageReady(photoId, sortOrder, type);
    }

    default void onImageFailed(String photoId, int sortOrder, String type) {
    }

    default void onImageFailed(String photoId, int sortOrder, String type, String sessionId) {
        onImageFailed(photoId, sortOrder, type);
    }
}
