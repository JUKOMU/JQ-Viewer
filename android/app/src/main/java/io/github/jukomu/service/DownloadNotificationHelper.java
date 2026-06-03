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
import androidx.core.app.NotificationCompat;
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

    public void showPreparing(int notificationId, String chapterTitle, String coverUrl) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("准备下载...")
            .setContentText(chapterTitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true);
        applyCover(builder, coverUrl);
        notify(notificationId, builder.build());
    }

    public void showProgress(int notificationId, String chapterTitle, String coverUrl,
                             int downloadedPages, int totalPages) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("正在下载")
            .setContentText(buildProgressText(chapterTitle, downloadedPages, totalPages))
            .setOngoing(true)
            .setOnlyAlertOnce(true);
        applyCover(builder, coverUrl);

        if (totalPages > 0) {
            builder.setProgress(totalPages, Math.min(downloadedPages, totalPages), false);
        } else {
            builder.setProgress(0, 0, true);
        }

        notify(notificationId, builder.build());
    }

    public void showPaused(int notificationId, String chapterTitle, String coverUrl,
                           int downloadedPages, int totalPages) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("下载已暂停")
            .setContentText(buildProgressText(chapterTitle, downloadedPages, totalPages))
            .setOngoing(false)
            .setOnlyAlertOnce(true);
        applyCover(builder, coverUrl);

        if (totalPages > 0) {
            builder.setProgress(totalPages, Math.min(downloadedPages, totalPages), false);
        }

        notify(notificationId, builder.build());
    }

    public void showComplete(int notificationId, String chapterTitle, String coverUrl) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("下载完成")
            .setContentText(chapterTitle)
            .setContentIntent(createLaunchIntent(notificationId))
            .setAutoCancel(true)
            .setOngoing(false);
        applyCover(builder, coverUrl);
        notify(notificationId, builder.build());
    }

    public void showError(int notificationId, String chapterTitle, String coverUrl, String error) {
        String message = error != null && !error.isEmpty() ? error : "下载失败";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("下载失败: " + chapterTitle)
            .setContentText(message)
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
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) {
            intent = new Intent();
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            context,
            notificationId,
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

    private String buildProgressText(String chapterTitle, int downloadedPages, int totalPages) {
        if (totalPages <= 0) {
            return chapterTitle;
        }
        int safeDownloaded = Math.max(0, Math.min(downloadedPages, totalPages));
        int percent = Math.round(safeDownloaded * 100f / totalPages);
        return chapterTitle + " (" + safeDownloaded + "/" + totalPages + " · " + percent + "%)";
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

            Bitmap cover = createTopCropCover(decoded);
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

    private Bitmap createTopCropCover(Bitmap source) {
        int cropSize = Math.min(source.getWidth(), source.getHeight());
        int left = Math.max(0, (source.getWidth() - cropSize) / 2);
        Bitmap cropped = Bitmap.createBitmap(source, left, 0, cropSize, cropSize);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, COVER_TARGET_SIZE, COVER_TARGET_SIZE, true);
        if (cropped != scaled) cropped.recycle();
        return scaled;
    }
}
