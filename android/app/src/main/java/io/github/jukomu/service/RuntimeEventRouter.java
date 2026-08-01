package io.github.jukomu.service;

/**
 * 进程级服务的可重新绑定事件出口。
 */
final class RuntimeEventRouter implements ServiceListener {

    private volatile ServiceListener delegate;

    synchronized void attach(ServiceListener listener) {
        delegate = listener;
    }

    synchronized void detach(ServiceListener expected) {
        if (delegate == expected) {
            delegate = null;
        }
    }

    @Override
    public void onDownloadProgress(DownloadProgressData data) {
        ServiceListener current = delegate;
        if (current != null) {
            current.onDownloadProgress(data);
        }
    }

    @Override
    public void onImageReady(String photoId, int sortOrder, String type) {
        ServiceListener current = delegate;
        if (current != null) {
            current.onImageReady(photoId, sortOrder, type);
        }
    }

    @Override
    public void onRelocationProgress(int current, int total, String phase,
                                     String currentFile) {
        ServiceListener listener = delegate;
        if (listener != null) {
            listener.onRelocationProgress(current, total, phase, currentFile);
        }
    }
}
