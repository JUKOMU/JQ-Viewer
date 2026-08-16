package io.github.jukomu.feature.update;

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
import androidx.core.content.ContextCompat;

import io.github.jukomu.MainActivity;
import io.github.jukomu.R;
import io.github.jukomu.platform.notification.NotificationIds;

import java.util.Locale;

/**
 * 应用内更新独立的前台通知服务。
 */
public final class UpdateForegroundService extends Service {

    private static final String TAG = "UpdateForegroundService";
    private static final String CHANNEL_ID = "app_update";
    private static final String CHANNEL_NAME = "应用更新";
    private static final String ACTION_UPDATE = "io.github.jukomu.UPDATE_FOREGROUND_UPDATE";
    private static final String ACTION_STOP = "io.github.jukomu.UPDATE_FOREGROUND_STOP";
    private static final String EXTRA_REVISION = "revision";
    private static final String EXTRA_PHASE = "phase";
    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_GITHUB_BYTES = "github_bytes";
    private static final String EXTRA_GITEE_BYTES = "gitee_bytes";
    private static final String EXTRA_TOTAL_BYTES = "total_bytes";
    private static final String EXTRA_SPEED_BYTES_PER_SECOND = "speed_bytes_per_second";
    private static final String EXTRA_ERROR = "error";
    private static final int ICON = R.mipmap.ic_launcher;

    private int lastRevision;

    /**
     * 发布当前更新进度；旧 revision 不得覆盖新通知。
     */
    public static void update(Context context, Snapshot snapshot) {
        Intent intent = new Intent(context, UpdateForegroundService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_REVISION, snapshot.revision);
        intent.putExtra(EXTRA_PHASE, snapshot.phase);
        intent.putExtra(EXTRA_SOURCE, snapshot.source);
        intent.putExtra(EXTRA_GITHUB_BYTES, snapshot.githubBytes);
        intent.putExtra(EXTRA_GITEE_BYTES, snapshot.giteeBytes);
        intent.putExtra(EXTRA_TOTAL_BYTES, snapshot.totalBytes);
        intent.putExtra(EXTRA_SPEED_BYTES_PER_SECOND, snapshot.speedBytesPerSecond);
        intent.putExtra(EXTRA_ERROR, snapshot.error);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "启动更新前台通知失败", error);
        }
    }

    /**
     * 清理更新通知并停止服务。
     */
    public static void stop(Context context, int revision) {
        Intent intent = new Intent(context, UpdateForegroundService.class);
        try {
            context.stopService(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "停止更新前台通知失败", error);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int revision = intent == null ? 0 : intent.getIntExtra(EXTRA_REVISION, 0);
        if (revision < lastRevision) {
            return START_NOT_STICKY;
        }
        lastRevision = revision;
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForegroundSafely();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            startForeground(NotificationIds.UPDATE_FOREGROUND,
                buildNotification(intent, revision));
            String phase = intent == null ? "" : intent.getStringExtra(EXTRA_PHASE);
            if (isTerminalPhase(phase)) {
                stopForeground(false);
                stopSelf(startId);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "创建更新前台通知失败", error);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean isStaleRevision(int revision, int lastRevision) {
        return revision < lastRevision;
    }

    static boolean isTerminalPhase(String phase) {
        return "failed".equals(phase) || "ready_to_install".equals(phase);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("应用内更新下载进度");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(Intent intent, int revision) {
        String phase = intent == null ? "更新准备中" : intent.getStringExtra(EXTRA_PHASE);
        String source = intent == null ? "" : intent.getStringExtra(EXTRA_SOURCE);
        long githubBytes = intent == null ? 0L : intent.getLongExtra(EXTRA_GITHUB_BYTES, 0L);
        long giteeBytes = intent == null ? 0L : intent.getLongExtra(EXTRA_GITEE_BYTES, 0L);
        long totalBytes = intent == null ? 0L : intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L);
        long speedBytesPerSecond = intent == null
            ? 0L : intent.getLongExtra(EXTRA_SPEED_BYTES_PER_SECOND, 0L);
        String error = intent == null ? "" : intent.getStringExtra(EXTRA_ERROR);
        String content = buildContent(phase, source, githubBytes, giteeBytes, totalBytes,
            speedBytesPerSecond, error);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("JQ Viewer 应用更新")
            .setContentText(content)
            .setContentIntent(createLaunchIntent())
            .setOngoing(!isTerminalPhase(phase))
            .setAutoCancel(isTerminalPhase(phase))
            .setOnlyAlertOnce(true);

        if (isTerminalPhase(phase)) {
            builder.setProgress(0, 0, false);
            return builder.build();
        }
        if ("verifying".equals(phase)) {
            builder.setProgress(0, 0, true);
            return builder.build();
        }
        if ("racing".equals(phase) || "selected".equals(phase)) {
            long displayedBytes = displayedBytes(source, githubBytes, giteeBytes, totalBytes);
            if (totalBytes > 0L) {
                builder.setProgress(100, progressPercent(displayedBytes, totalBytes), false);
            } else {
                builder.setProgress(0, 0, true);
            }
        }
        return builder.build();
    }

    static String buildContent(String phase, String source, long githubBytes, long giteeBytes,
                               long totalBytes, long speedBytesPerSecond, String error) {
        if ("failed".equals(phase) && error != null && !error.isEmpty()) {
            return "更新失败 · " + error;
        }
        if ("racing".equals(phase) || "selected".equals(phase)) {
            long downloaded = displayedBytes(source, githubBytes, giteeBytes, totalBytes);
            return "下载中 " + progressPercent(downloaded, totalBytes) + "%  "
                + formatMiBPair(downloaded, totalBytes) + "  "
                + formatMiB(speedBytesPerSecond) + "/s";
        }
        if ("verifying".equals(phase)) {
            return "校验中";
        }
        if ("ready_to_install".equals(phase)) {
            return "准备安装";
        }
        if ("failed".equals(phase)) {
            return "更新失败";
        }
        return "更新中";
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.US, "%.1f MiB", Math.max(0L, bytes)
            / (1024.0 * 1024.0));
    }

    private static String formatMiBPair(long downloadedBytes, long totalBytes) {
        return String.format(Locale.US, "%.1f / %.1f MiB",
            Math.max(0L, downloadedBytes) / (1024.0 * 1024.0),
            Math.max(0L, totalBytes) / (1024.0 * 1024.0));
    }

    private static int progressPercent(long downloadedBytes, long totalBytes) {
        if (totalBytes <= 0L) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(100L,
            Math.round(downloadedBytes * 100.0 / totalBytes)));
    }

    private static long displayedBytes(String source, long githubBytes, long giteeBytes,
                                       long totalBytes) {
        long selected = "GitHub".equals(source) ? githubBytes
            : "Gitee".equals(source) ? giteeBytes : Math.max(githubBytes, giteeBytes);
        return totalBytes > 0L ? Math.min(Math.max(0L, selected), totalBytes)
            : Math.max(0L, selected);
    }

    private PendingIntent createLaunchIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(MainActivity.ACTION_OPEN_ROUTE);
        intent.putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_ABOUT);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, NotificationIds.UPDATE_FOREGROUND, intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void stopForegroundSafely() {
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException error) {
            Log.w(TAG, "移除更新前台通知失败", error);
        }
    }

    /**
     * 更新通知使用的不可变快照。
     */
    public static final class Snapshot {
        public final int revision;
        public final String phase;
        public final String source;
        public final long githubBytes;
        public final long giteeBytes;
        public final long totalBytes;
        public final long speedBytesPerSecond;
        public final String error;

        public Snapshot(int revision, String phase, String source, long githubBytes,
                        long giteeBytes, long totalBytes, long speedBytesPerSecond,
                        String error) {
            this.revision = revision;
            this.phase = phase == null ? "" : phase;
            this.source = source == null ? "" : source;
            this.githubBytes = Math.max(0L, githubBytes);
            this.giteeBytes = Math.max(0L, giteeBytes);
            this.totalBytes = Math.max(0L, totalBytes);
            this.speedBytesPerSecond = Math.max(0L, speedBytesPerSecond);
            this.error = error == null ? "" : error;
        }
    }
}
