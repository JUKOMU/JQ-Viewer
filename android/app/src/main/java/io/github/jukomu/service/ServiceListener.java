package io.github.jukomu.service;

/**
 * 统一事件回调——service 层通过此接口向 bridge 层推送事件。
 * bridge 层实现此接口，将事件转发为 Capacitor notifyListeners() 调用。
 */
public interface ServiceListener {
    void onDownloadProgress(DownloadProgressData data);

    void onImageReady(String photoId, int sortOrder, String type);

    void onRelocationProgress(int current, int total, String phase, String currentFile);
}
