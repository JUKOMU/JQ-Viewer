package io.github.jukomu.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import io.github.jukomu.MainActivity;
import io.github.jukomu.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 章节下载系统通知辅助类。
 */
public class DownloadNotificationHelper {

    private static final String TAG = "DownloadNotification";
    private static final String CHANNEL_ID = "chapter_download";
    private static final String CHANNEL_NAME = "章节下载";
    private static final int QUEUED_SUMMARY_ID = 203000;
    private static final int ICON = R.mipmap.ic_launcher;
    private static final int MAX_COVER_BYTES = 3 * 1024 * 1024;
    private static final int COVER_TARGET_SIZE = 256;

    private final Context context;
    private final NotificationManager manager;
    private final Map<String, String> queuedTasks = new ConcurrentHashMap<>();
    private final Map<String, Bitmap> coverCache = new ConcurrentHashMap<>();

    public DownloadNotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("章节下载进度通知");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    public void showQueued(String taskId, String chapterTitle) {
        queuedTasks.put(taskId, chapterTitle);
        updateQueuedSummary();
    }

    public void removeQueued(String taskId) {
        queuedTasks.remove(taskId);
        updateQueuedSummary();
    }

    private void updateQueuedSummary() {
        if (queuedTasks.isEmpty()) {
            cancel(QUEUED_SUMMARY_ID);
            return;
        }

        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle()
            .setBigContentTitle("下载队列 (" + queuedTasks.size() + ")");
        int count = 0;
        for (String title : queuedTasks.values()) {
            if (count >= 5) break;
            style.addLine(title);
            count++;
        }
        if (queuedTasks.size() > count) {
            style.setSummaryText("还有 " + (queuedTasks.size() - count) + " 个任务");
        }

        notify(QUEUED_SUMMARY_ID, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("下载队列")
            .setContentText("已排队 " + queuedTasks.size() + " 个章节")
            .setStyle(style)
            .setContentIntent(createLaunchIntent(QUEUED_SUMMARY_ID))
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .build());
    }

    public void showPreparing(int notificationId, String taskId, String albumTitle,
                              String chapterId, String chapterTitle, String coverUrl) {
        NotificationCompat.Builder builder = buildTaskNotification(
            taskId, albumTitle, chapterId, chapterTitle, coverUrl,
            0, 0, "准备下载", true);
        builder.addAction(ICON, "取消", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_CANCEL, taskId));
        notify(notificationId, builder.build());
    }

    public void showProgress(int notificationId, String taskId, String albumTitle,
                             String chapterId, String chapterTitle, String coverUrl,
                             int downloadedPages, int totalPages) {
        NotificationCompat.Builder builder = buildTaskNotification(
            taskId, albumTitle, chapterId, chapterTitle, coverUrl,
            downloadedPages, totalPages, "正在下载", true);
        builder.addAction(ICON, "暂停", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_PAUSE, taskId));
        builder.addAction(ICON, "取消", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_CANCEL, taskId));
        notify(notificationId, builder.build());
    }

    public void showPaused(int notificationId, String taskId, String albumTitle,
                           String chapterId, String chapterTitle, String coverUrl,
                           int downloadedPages, int totalPages) {
        NotificationCompat.Builder builder = buildTaskNotification(
            taskId, albumTitle, chapterId, chapterTitle, coverUrl,
            downloadedPages, totalPages, "下载已暂停", false);
        builder.addAction(ICON, "继续", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_RESUME, taskId));
        builder.addAction(ICON, "取消", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_CANCEL, taskId));
        notify(notificationId, builder.build());
    }

    public void showComplete(int notificationId, String albumTitle, String chapterId,
                             String chapterTitle, String coverUrl) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("下载完成")
            .setContentText(buildTitle(albumTitle, chapterTitle))
            .setSubText(buildIdText(chapterId))
            .setContentIntent(createLaunchIntent(notificationId))
            .setAutoCancel(true)
            .setOngoing(false);
        applyCover(builder, coverUrl);
        notify(notificationId, builder.build());
    }

    public void showError(int notificationId, String albumTitle, String chapterId,
                          String chapterTitle, String coverUrl, String error) {
        String message = error != null && !error.isEmpty() ? error : "下载失败";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("下载失败: " + buildTitle(albumTitle, chapterTitle))
            .setContentText(message)
            .setSubText(buildIdText(chapterId))
            .setContentIntent(createLaunchIntent(notificationId))
            .setAutoCancel(true)
            .setOngoing(false);
        applyCover(builder, coverUrl);
        notify(notificationId, builder.build());
    }

    public void cancel(int notificationId) {
        if (manager != null) {
            manager.cancel(notificationId);
        }
    }

    private PendingIntent createLaunchIntent(int notificationId) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(MainActivity.ACTION_OPEN_ROUTE);
        intent.putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_DOWNLOAD);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private PendingIntent createActionIntent(String action, String taskId) {
        Intent intent = new Intent(context, DownloadNotificationActionReceiver.class);
        intent.setAction(action);
        intent.putExtra(DownloadNotificationActionReceiver.EXTRA_TASK_ID, taskId);
        return PendingIntent.getBroadcast(
            context,
            (taskId + action).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private void notify(int notificationId, Notification notification) {
        if (manager == null) return;
        try {
            manager.notify(notificationId, notification);
        } catch (SecurityException e) {
            Log.d(TAG, "通知权限未授予，跳过下载通知", e);
        }
    }

    private void applyCover(NotificationCompat.Builder builder, String coverUrl) {
        Bitmap cover = loadCover(coverUrl);
        if (cover != null) {
            builder.setLargeIcon(cover);
        }
    }

    private NotificationCompat.Builder buildTaskNotification(
        String taskId, String albumTitle, String chapterId, String chapterTitle,
        String coverUrl, int downloadedPages, int totalPages, String stateText,
        boolean ongoing) {
        String title = buildTitle(albumTitle, chapterTitle);
        String statusText = buildProgressText(stateText, downloadedPages, totalPages);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSubText(buildIdText(chapterId))
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(buildTaskRemoteViews(
                R.layout.notification_download_compact, false, albumTitle, chapterId,
                chapterTitle, coverUrl, downloadedPages, totalPages, stateText))
            .setCustomBigContentView(buildTaskRemoteViews(
                R.layout.notification_download_expanded, true, albumTitle, chapterId,
                chapterTitle, coverUrl, downloadedPages, totalPages, stateText))
            .setContentIntent(createLaunchIntent(notificationIdFromTask(taskId)))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true);

        applyCover(builder, coverUrl);
        return builder;
    }

    private RemoteViews buildTaskRemoteViews(int layoutId, boolean expanded,
                                             String albumTitle, String chapterId,
                                             String chapterTitle, String coverUrl,
                                             int downloadedPages, int totalPages,
                                             String stateText) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layoutId);
        views.setTextViewText(R.id.notification_download_title, buildTitle(albumTitle, chapterTitle));
        views.setTextViewText(R.id.notification_download_id, buildIdText(chapterId));
        views.setTextViewText(R.id.notification_download_status,
            buildProgressText(stateText, downloadedPages, totalPages));
        if (expanded) {
            views.setTextViewText(R.id.notification_download_chapter, safeText(chapterTitle, "章节"));
        }

        Bitmap cover = loadCover(coverUrl);
        if (cover != null) {
            views.setImageViewBitmap(R.id.notification_download_cover, cover);
        } else {
            views.setImageViewResource(R.id.notification_download_cover, ICON);
        }

        if (totalPages > 0) {
            views.setProgressBar(
                R.id.notification_download_progress,
                totalPages,
                Math.max(0, Math.min(downloadedPages, totalPages)),
                false);
        } else {
            views.setProgressBar(R.id.notification_download_progress, 100, 0, true);
        }
        return views;
    }

    private String buildProgressText(String stateText, int downloadedPages, int totalPages) {
        if (totalPages <= 0) {
            return stateText;
        }
        int safeDownloaded = Math.max(0, Math.min(downloadedPages, totalPages));
        int percent = Math.round(safeDownloaded * 100f / totalPages);
        return stateText + " · " + safeDownloaded + "/" + totalPages + " · " + percent + "%";
    }

    private String buildTitle(String albumTitle, String chapterTitle) {
        if (albumTitle != null && !albumTitle.isEmpty()) return albumTitle;
        return safeText(chapterTitle, "章节下载");
    }

    private String buildIdText(String chapterId) {
        return "ID " + safeText(chapterId, "-");
    }

    private String safeText(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private int notificationIdFromTask(String taskId) {
        return 3000 + ((taskId.hashCode() & 0x7fffffff) % 100000);
    }

    private Bitmap loadCover(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return null;
        Bitmap cached = coverCache.get(coverUrl);
        if (cached != null) return cached;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(coverUrl).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Referer", "https://18comic.vip/");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            byte[] data = readLimited(conn.getInputStream());
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight);
            Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (decoded == null) return null;

            Bitmap cover = createBoundedCover(decoded);
            if (decoded != cover) decoded.recycle();
            coverCache.put(coverUrl, cover);
            return cover;
        } catch (Exception e) {
            Log.d(TAG, "下载通知封面加载失败", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private byte[] readLimited(InputStream input) throws java.io.IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int len;
            while ((len = in.read(buffer)) > 0) {
                total += len;
                if (total > MAX_COVER_BYTES) {
                    throw new java.io.IOException("cover too large");
                }
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        }
    }

    private int calculateSampleSize(int width, int height) {
        int sample = 1;
        while (width / sample > COVER_TARGET_SIZE * 2 || height / sample > COVER_TARGET_SIZE * 2) {
            sample *= 2;
        }
        return sample;
    }

    private Bitmap createBoundedCover(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int maxSide = Math.max(width, height);
        if (maxSide <= COVER_TARGET_SIZE) return source;
        float scale = COVER_TARGET_SIZE / (float) maxSide;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }
}
