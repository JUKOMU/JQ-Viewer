package io.github.jukomu.feature.download;

import android.util.Log;

import io.github.jukomu.feature.download.notification.DownloadNotificationActionReceiver;

/**
 * 将下载通知动作转发给当前会话的命令执行者。
 */
public final class DownloadCommandRouter {

    private static final String TAG = "DownloadCommandRouter";
    private static final DownloadCommandRouter INSTANCE = new DownloadCommandRouter();

    private volatile DownloadCommandPort delegate;

    public DownloadCommandRouter() {
    }

    public static DownloadCommandRouter getInstance() {
        return INSTANCE;
    }

    public synchronized void attach(DownloadCommandPort commandPort) {
        delegate = commandPort;
    }

    public synchronized void detach(DownloadCommandPort expected) {
        if (delegate == expected) {
            delegate = null;
        }
    }

    public void dispatch(String action, String taskId) {
        DownloadCommandPort current = delegate;
        if (current == null) {
            Log.w(TAG, "下载通知操作到达时服务未就绪: " + action + ", " + taskId);
            return;
        }
        try {
            if (DownloadNotificationActionReceiver.ACTION_PAUSE.equals(action)) {
                current.pauseDownload(taskId);
            } else if (DownloadNotificationActionReceiver.ACTION_RESUME.equals(action)) {
                current.resumeDownload(taskId);
            } else if (DownloadNotificationActionReceiver.ACTION_CANCEL.equals(action)) {
                current.cancelDownload(taskId);
            }
        } catch (Exception error) {
            Log.w(TAG, "处理下载通知操作失败: " + action + ", " + taskId, error);
        }
    }
}
