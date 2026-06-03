package io.github.jukomu.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import io.github.jukomu.bridge.JmcomicPlugin;

public class DownloadNotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_PAUSE = "io.github.jukomu.DOWNLOAD_NOTIFICATION_PAUSE";
    public static final String ACTION_RESUME = "io.github.jukomu.DOWNLOAD_NOTIFICATION_RESUME";
    public static final String ACTION_CANCEL = "io.github.jukomu.DOWNLOAD_NOTIFICATION_CANCEL";
    public static final String EXTRA_TASK_ID = "task_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String taskId = intent.getStringExtra(EXTRA_TASK_ID);
        if (taskId == null || taskId.isEmpty()) return;
        JmcomicPlugin.handleDownloadNotificationAction(intent.getAction(), taskId);
    }
}
