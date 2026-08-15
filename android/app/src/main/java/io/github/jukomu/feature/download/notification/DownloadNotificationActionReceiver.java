package io.github.jukomu.feature.download.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.jukomu.feature.download.DownloadCommandRouter;

/**
 * 接收下载通知动作并提交对应的任务控制命令。
 */
public class DownloadNotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_PAUSE = "io.github.jukomu.DOWNLOAD_NOTIFICATION_PAUSE";
    public static final String ACTION_RESUME = "io.github.jukomu.DOWNLOAD_NOTIFICATION_RESUME";
    public static final String ACTION_CANCEL = "io.github.jukomu.DOWNLOAD_NOTIFICATION_CANCEL";
    public static final String EXTRA_TASK_ID = "task_id";

    private final DownloadCommandRouter commandRouter;

    public DownloadNotificationActionReceiver() {
        this(DownloadCommandRouter.getInstance());
    }

    DownloadNotificationActionReceiver(DownloadCommandRouter commandRouter) {
        this.commandRouter = commandRouter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String taskId = intent.getStringExtra(EXTRA_TASK_ID);
        if (taskId == null || taskId.isEmpty()) return;
        commandRouter.dispatch(intent.getAction(), taskId);
    }
}
