package io.github.jukomu.service;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final String EXTRA_SESSION_ID = "session_id";
    private static final String EXTRA_REVISION = "revision";
    private static final String EXTRA_ACTIVE_COUNT = "active_count";
    private static final String EXTRA_QUEUE_REMAINING = "queue_remaining";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_PHASE = "phase";
    private static final String EXTRA_CURRENT_PAGE = "current_page";
    private static final String EXTRA_TOTAL_PAGES = "total_pages";
    private static final String EXTRA_VOLUME_INDEX = "volume_index";
    private static final String EXTRA_TOTAL_VOLUMES = "total_volumes";
    private static final int ICON = R.mipmap.ic_launcher;
    private static final long MIN_NOTIFY_INTERVAL_MS = 1000L;
    private static final String BACKGROUND_KEEPALIVE_HINT =
        "若切到后台后进度不动，请在最近任务中锁定应用，或在系统电池/后台管理中允许后台运行后重试。";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastRevision = 0;
    private boolean startedForeground = false;
    private boolean flushScheduled = false;
    private long lastNotifyAt = 0L;
    private int displayedSessionId = 0;
    private String displayedPhase = "";
    private Snapshot latestSnapshot;
    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            flushNow();
        }
    };

    public static void update(Context context, Snapshot snapshot) {
        Intent intent = new Intent(context, PdfExportForegroundService.class);
        intent.setAction(snapshot.activeCount > 0 ? ACTION_UPDATE : ACTION_STOP);
        intent.putExtra(EXTRA_SESSION_ID, snapshot.sessionId);
        intent.putExtra(EXTRA_REVISION, snapshot.revision);
        intent.putExtra(EXTRA_ACTIVE_COUNT, snapshot.activeCount);
        intent.putExtra(EXTRA_QUEUE_REMAINING, snapshot.queueRemaining);
        intent.putExtra(EXTRA_TITLE, snapshot.title);
        intent.putExtra(EXTRA_PHASE, snapshot.phase);
        intent.putExtra(EXTRA_CURRENT_PAGE, snapshot.currentPage);
        intent.putExtra(EXTRA_TOTAL_PAGES, snapshot.totalPages);
        intent.putExtra(EXTRA_VOLUME_INDEX, snapshot.volumeIndex);
        intent.putExtra(EXTRA_TOTAL_VOLUMES, snapshot.totalVolumes);
        try {
            if (snapshot.activeCount > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        int revision = intent != null ? intent.getIntExtra(EXTRA_REVISION, 0) : 0;
        if (isStaleRevision(revision, lastRevision)) {
            return START_NOT_STICKY;
        }
        lastRevision = revision;

        if (ACTION_STOP.equals(action)) {
            handler.removeCallbacks(flushRunnable);
            flushScheduled = false;
            latestSnapshot = null;
            stopForegroundSafely();
            stopSelf();
            return START_NOT_STICKY;
        }

        latestSnapshot = parseSnapshot(intent, revision);
        flushOrSchedule();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(flushRunnable);
        super.onDestroy();
    }

    static boolean isStaleRevision(int revision, int lastRevision) {
        return revision < lastRevision;
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

    private Snapshot parseSnapshot(Intent intent, int revision) {
        if (intent == null) {
            return new Snapshot(0, revision, 1, 0, "PDF 导出", "进行中", 0, 0, 0, 0);
        }
        return new Snapshot(
            intent.getIntExtra(EXTRA_SESSION_ID, 0),
            revision,
            intent.getIntExtra(EXTRA_ACTIVE_COUNT, 1),
            intent.getIntExtra(EXTRA_QUEUE_REMAINING, 0),
            intent.getStringExtra(EXTRA_TITLE),
            intent.getStringExtra(EXTRA_PHASE),
            intent.getIntExtra(EXTRA_CURRENT_PAGE, 0),
            intent.getIntExtra(EXTRA_TOTAL_PAGES, 0),
            intent.getIntExtra(EXTRA_VOLUME_INDEX, 0),
            intent.getIntExtra(EXTRA_TOTAL_VOLUMES, 0)
        );
    }

    private void flushOrSchedule() {
        Snapshot snapshot = latestSnapshot;
        if (snapshot == null || snapshot.activeCount <= 0) {
            return;
        }
        if (!startedForeground || shouldFlushImmediately(snapshot)) {
            handler.removeCallbacks(flushRunnable);
            flushScheduled = false;
            flushNow();
            return;
        }

        long elapsed = SystemClock.uptimeMillis() - lastNotifyAt;
        if (elapsed >= MIN_NOTIFY_INTERVAL_MS) {
            flushNow();
        } else if (!flushScheduled) {
            flushScheduled = true;
            handler.postDelayed(flushRunnable, MIN_NOTIFY_INTERVAL_MS - elapsed);
        }
    }

    private boolean shouldFlushImmediately(Snapshot snapshot) {
        return snapshot.sessionId != displayedSessionId
            || !snapshot.phase.equals(displayedPhase)
            || (snapshot.totalPages > 0 && snapshot.currentPage >= snapshot.totalPages);
    }

    private void flushNow() {
        Snapshot snapshot = latestSnapshot;
        if (snapshot == null || snapshot.activeCount <= 0) {
            return;
        }
        try {
            startForeground(NotificationIds.PDF_FOREGROUND, buildNotification(snapshot));
            startedForeground = true;
            lastNotifyAt = SystemClock.uptimeMillis();
            displayedSessionId = snapshot.sessionId;
            displayedPhase = snapshot.phase;
        } catch (Exception e) {
            Log.w(TAG, "启动 PDF 前台通知失败，导出任务继续由 PdfExportService 推进", e);
            stopSelf();
        }
    }

    private Notification buildNotification(Snapshot snapshot) {
        String contentText = buildContentText(snapshot);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("PDF 导出进行中")
            .setContentText(contentText)
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(buildExpandedContentText(contentText)))
            .setContentIntent(createLaunchIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true);

        if (snapshot.totalPages > 0) {
            int currentPage = Math.max(0, Math.min(snapshot.currentPage, snapshot.totalPages));
            builder.setProgress(snapshot.totalPages, currentPage, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private String buildContentText(Snapshot snapshot) {
        StringBuilder text = new StringBuilder();
        text.append(snapshot.phase).append(": ").append(snapshot.title);
        if (snapshot.totalPages > 0) {
            text.append(" (")
                .append(Math.max(0, Math.min(snapshot.currentPage, snapshot.totalPages)))
                .append("/")
                .append(snapshot.totalPages)
                .append(")");
        }
        if (snapshot.totalVolumes > 1 && snapshot.volumeIndex > 0) {
            text.append(" · 分卷 ")
                .append(snapshot.volumeIndex)
                .append("/")
                .append(snapshot.totalVolumes);
        }
        if (snapshot.queueRemaining > 0) {
            text.append(" · 队列剩余 ").append(snapshot.queueRemaining);
        }
        return text.toString();
    }

    private String buildExpandedContentText(String contentText) {
        return contentText + "\n" + BACKGROUND_KEEPALIVE_HINT;
    }

    private PendingIntent createLaunchIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) {
            intent = new Intent();
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            this,
            NotificationIds.PDF_FOREGROUND,
            intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private void stopForegroundSafely() {
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            Log.d(TAG, "停止 PDF 前台通知失败", e);
        }
    }

    static final class Snapshot {
        final int sessionId;
        final int revision;
        final int activeCount;
        final int queueRemaining;
        final String title;
        final String phase;
        final int currentPage;
        final int totalPages;
        final int volumeIndex;
        final int totalVolumes;

        Snapshot(int sessionId, int revision, int activeCount, int queueRemaining, String title,
                 String phase, int currentPage, int totalPages, int volumeIndex, int totalVolumes) {
            this.sessionId = sessionId;
            this.revision = revision;
            this.activeCount = Math.max(0, activeCount);
            this.queueRemaining = Math.max(0, queueRemaining);
            this.title = title == null || title.isEmpty() ? "PDF 导出" : title;
            this.phase = phase == null || phase.isEmpty() ? "进行中" : phase;
            this.currentPage = Math.max(0, currentPage);
            this.totalPages = Math.max(0, totalPages);
            this.volumeIndex = Math.max(0, volumeIndex);
            this.totalVolumes = Math.max(0, totalVolumes);
        }
    }
}
