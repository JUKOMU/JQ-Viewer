package io.github.jukomu.feature.download.notification;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import io.github.jukomu.MainActivity;
import io.github.jukomu.R;
import io.github.jukomu.platform.notification.NotificationIds;

/**
 * Keeps active chapter downloads in foreground priority while the app is backgrounded.
 */
public class DownloadForegroundService extends Service {

    private static final String TAG = "DownloadForegroundService";
    private static final String CHANNEL_ID = "chapter_download";
    private static final String CHANNEL_NAME = "章节下载";
    private static final String ACTION_UPDATE = "io.github.jukomu.DOWNLOAD_FOREGROUND_UPDATE";
    private static final String ACTION_STOP = "io.github.jukomu.DOWNLOAD_FOREGROUND_STOP";
    private static final String EXTRA_ACTIVE_COUNT = "active_count";
    private static final String EXTRA_REVISION = "revision";
    private static final int ICON = R.mipmap.ic_launcher;
    private int lastRevision = 0;

    public static void update(Context context, int activeCount, int revision) {
        Intent intent = new Intent(context, DownloadForegroundService.class);
        intent.setAction(activeCount > 0 ? ACTION_UPDATE : ACTION_STOP);
        intent.putExtra(EXTRA_ACTIVE_COUNT, activeCount);
        intent.putExtra(EXTRA_REVISION, revision);
        try {
            if (activeCount > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.d(TAG, "更新下载前台服务失败", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        int revision = intent != null ? intent.getIntExtra(EXTRA_REVISION, 0) : 0;
        if (isStaleRevision(revision, lastRevision)) {
            return START_NOT_STICKY;
        }
        lastRevision = revision;

        if (ACTION_STOP.equals(action)) {
            stopForegroundSafely();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        int activeCount = intent != null ? intent.getIntExtra(EXTRA_ACTIVE_COUNT, 1) : 1;
        try {
            startForeground(NotificationIds.DOWNLOAD_FOREGROUND, buildNotification(Math.max(1, activeCount)));
        } catch (Exception e) {
            Log.w(TAG, "启动下载前台通知失败，任务继续由下载服务推进", e);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    static boolean isStaleRevision(int revision, int lastRevision) {
        return revision < lastRevision;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("章节下载进度通知");
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(int activeCount) {
        String text = activeCount == 1
            ? "1 个章节正在后台下载"
            : activeCount + " 个章节正在后台下载";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("下载进行中")
            .setContentText(text)
            .setContentIntent(createLaunchIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private PendingIntent createLaunchIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(MainActivity.ACTION_OPEN_ROUTE);
        intent.putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_DOWNLOAD);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            this,
            NotificationIds.DOWNLOAD_FOREGROUND,
            intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private void stopForegroundSafely() {
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            Log.d(TAG, "停止下载前台通知失败", e);
        }
    }
}
