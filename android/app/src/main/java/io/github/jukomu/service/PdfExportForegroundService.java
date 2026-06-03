package io.github.jukomu.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import io.github.jukomu.R;

/**
 * Keeps PDF export jobs in foreground priority while the app is backgrounded.
 */
public class PdfExportForegroundService extends Service {

    private static final String TAG = "PdfExportForegroundService";
    private static final String CHANNEL_ID = "pdf_export";
    private static final String CHANNEL_NAME = "PDF导出";
    private static final String ACTION_UPDATE = "io.github.jukomu.PDF_EXPORT_FOREGROUND_UPDATE";
    private static final String ACTION_STOP = "io.github.jukomu.PDF_EXPORT_FOREGROUND_STOP";
    private static final String EXTRA_ACTIVE_COUNT = "active_count";
    private static final int NOTIFICATION_ID = 202001;
    private static final int ICON = R.mipmap.ic_launcher;

    public static void update(Context context, int activeCount) {
        Intent intent = new Intent(context, PdfExportForegroundService.class);
        intent.setAction(activeCount > 0 ? ACTION_UPDATE : ACTION_STOP);
        intent.putExtra(EXTRA_ACTIVE_COUNT, activeCount);
        try {
            if (activeCount > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.d(TAG, "更新 PDF 导出前台服务失败", e);
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
        if (ACTION_STOP.equals(action)) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        int activeCount = intent != null ? intent.getIntExtra(EXTRA_ACTIVE_COUNT, 1) : 1;
        startForeground(NOTIFICATION_ID, buildNotification(Math.max(1, activeCount)));
        return START_NOT_STICKY;
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
            channel.setDescription("PDF导出进度通知");
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(int activeCount) {
        String text = activeCount == 1
            ? "1 个 PDF 导出任务正在后台处理"
            : activeCount + " 个 PDF 导出任务正在后台处理";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("PDF 导出进行中")
            .setContentText(text)
            .setContentIntent(createLaunchIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private PendingIntent createLaunchIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) {
            intent = new Intent();
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }
}
