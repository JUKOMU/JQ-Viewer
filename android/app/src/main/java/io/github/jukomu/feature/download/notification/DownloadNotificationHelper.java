package io.github.jukomu.feature.download.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import io.github.jukomu.MainActivity;
import io.github.jukomu.R;
import io.github.jukomu.platform.notification.NotificationIds;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 章节下载系统通知辅助类。
 */
public class DownloadNotificationHelper {

    private static final String TAG = "DownloadNotification";
    private static final String CHANNEL_ID = "chapter_download";
    private static final String CHANNEL_NAME = "章节下载";
    private static final int ICON = R.mipmap.ic_launcher;
    private static final int MAX_COVER_BYTES = 3 * 1024 * 1024;
    private static final int COVER_TARGET_SIZE = 256;
    private static final int MAX_VISIBLE_TASK_NOTIFICATIONS = 12;
    private static final int MAX_RECENT_RESULTS = 5;
    private static final long INVALID_NOTIFICATION_VERSION = -1L;

    private final Context context;
    private final NotificationManager manager;
    private final Map<String, Bitmap> coverCache = new ConcurrentHashMap<>();
    private final Object notificationStateLock = new Object();
    private final Set<String> pendingTaskIds = new HashSet<>();
    private final Set<String> visibleTaskIds = new HashSet<>();
    private final Set<String> pausedTaskIds = new LinkedHashSet<>();
    private final Map<String, Long> taskNotificationVersions = new HashMap<>();
    private final ArrayDeque<String> recentCompleted = new ArrayDeque<>();
    private final ArrayDeque<String> recentFailed = new ArrayDeque<>();
    private long nextNotificationVersion;
    private int completedCount;
    private int failedCount;
    private int cancelledCount;

    public DownloadNotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        clearStaleDownloadNotifications();
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

    public void registerTask(String taskId) {
        synchronized (notificationStateLock) {
            if (pendingTaskIds.isEmpty()) {
                for (String visibleTaskId : visibleTaskIds) {
                    cancel(NotificationIds.downloadTask(visibleTaskId));
                }
                visibleTaskIds.clear();
                pausedTaskIds.clear();
                taskNotificationVersions.clear();
                completedCount = 0;
                failedCount = 0;
                cancelledCount = 0;
                recentCompleted.clear();
                recentFailed.clear();
                cancel(NotificationIds.DOWNLOAD_COMPLETED_SUMMARY);
                cancel(NotificationIds.DOWNLOAD_FAILED_SUMMARY);
            }
            pendingTaskIds.add(taskId);
        }
    }

    public void showProgress(int notificationId, String taskId, String albumTitle,
                             String chapterId, String chapterTitle, String coverUrl,
                             int downloadedPages, int totalPages) {
        long version = beginTaskNotificationUpdate(taskId, true);
        if (version == INVALID_NOTIFICATION_VERSION) return;
        NotificationCompat.Builder builder = buildTaskNotification(
            taskId, albumTitle, chapterId, chapterTitle, coverUrl,
            downloadedPages, totalPages, "正在下载", true);
        builder.addAction(ICON, "暂停", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_PAUSE, taskId));
        builder.addAction(ICON, "取消", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_CANCEL, taskId));
        publishTaskNotification(notificationId, taskId, version, builder.build());
    }

    public void showVerifying(int notificationId, String taskId, String albumTitle,
                              String chapterId, String chapterTitle, String coverUrl) {
        long version = beginTaskNotificationUpdate(taskId, true);
        if (version == INVALID_NOTIFICATION_VERSION) return;
        NotificationCompat.Builder builder = buildTaskNotification(
            taskId, albumTitle, chapterId, chapterTitle, coverUrl,
            0, 0, "正在校验", true);
        publishTaskNotification(notificationId, taskId, version, builder.build());
    }

    public void showPaused(int notificationId, String taskId, String albumTitle,
                           String chapterId, String chapterTitle, String coverUrl,
                           int downloadedPages, int totalPages) {
        long version = beginTaskNotificationUpdate(taskId, false);
        if (version == INVALID_NOTIFICATION_VERSION) return;
        NotificationCompat.Builder builder = buildTaskNotification(
            taskId, albumTitle, chapterId, chapterTitle, coverUrl,
            downloadedPages, totalPages, "下载已暂停", false);
        builder.addAction(ICON, "继续", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_RESUME, taskId));
        builder.addAction(ICON, "取消", createActionIntent(
            DownloadNotificationActionReceiver.ACTION_CANCEL, taskId));
        publishTaskNotification(notificationId, taskId, version, builder.build());
    }

    public void showComplete(int notificationId, String taskId, String albumTitle,
                             String chapterId, String chapterTitle) {
        synchronized (notificationStateLock) {
            releaseTaskNotificationLocked(notificationId, taskId);
            if (!pendingTaskIds.remove(taskId)) return;
            completedCount++;
            addRecentResult(recentCompleted,
                buildResultTitle(albumTitle, chapterId, chapterTitle));
            notify(NotificationIds.DOWNLOAD_COMPLETED_SUMMARY,
                buildCompletedSummaryLocked());
        }
    }

    public void showError(int notificationId, String taskId, String albumTitle,
                          String chapterId, String chapterTitle, String error) {
        String message = error != null && !error.isEmpty() ? error : "下载失败";
        synchronized (notificationStateLock) {
            releaseTaskNotificationLocked(notificationId, taskId);
            if (!pendingTaskIds.remove(taskId)) return;
            failedCount++;
            addRecentResult(recentFailed,
                buildResultTitle(albumTitle, chapterId, chapterTitle) + ": " + message);
            notify(NotificationIds.DOWNLOAD_FAILED_SUMMARY,
                buildFailedSummaryLocked());
        }
    }

    public void cancelTask(int notificationId, String taskId) {
        synchronized (notificationStateLock) {
            releaseTaskNotificationLocked(notificationId, taskId);
            if (pendingTaskIds.remove(taskId)) {
                cancelledCount++;
            }
        }
    }

    public void cancel(int notificationId) {
        if (manager != null) {
            try {
                manager.cancel(notificationId);
            } catch (SecurityException e) {
                Log.d(TAG, "通知权限未授予，跳过取消下载通知", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "取消下载通知失败", e);
            }
        }
    }

    private long beginTaskNotificationUpdate(String taskId, boolean active) {
        synchronized (notificationStateLock) {
            if (!pendingTaskIds.contains(taskId)) return INVALID_NOTIFICATION_VERSION;

            if (!visibleTaskIds.contains(taskId)) {
                if (visibleTaskIds.size() >= MAX_VISIBLE_TASK_NOTIFICATIONS) {
                    if (!active || pausedTaskIds.isEmpty()) {
                        return INVALID_NOTIFICATION_VERSION;
                    }
                    Iterator<String> pausedTasks = pausedTaskIds.iterator();
                    String evictedTaskId = pausedTasks.next();
                    pausedTasks.remove();
                    visibleTaskIds.remove(evictedTaskId);
                    taskNotificationVersions.remove(evictedTaskId);
                    cancel(NotificationIds.downloadTask(evictedTaskId));
                }
                visibleTaskIds.add(taskId);
            }

            if (active) {
                pausedTaskIds.remove(taskId);
            } else {
                pausedTaskIds.add(taskId);
            }

            long version = ++nextNotificationVersion;
            taskNotificationVersions.put(taskId, version);
            return version;
        }
    }

    private void publishTaskNotification(int notificationId, String taskId,
                                         long version, Notification notification) {
        synchronized (notificationStateLock) {
            Long currentVersion = taskNotificationVersions.get(taskId);
            if (!pendingTaskIds.contains(taskId)
                || !visibleTaskIds.contains(taskId)
                || currentVersion == null
                || currentVersion != version) {
                return;
            }
            notify(notificationId, notification);
        }
    }

    private void releaseTaskNotificationLocked(int notificationId, String taskId) {
        visibleTaskIds.remove(taskId);
        pausedTaskIds.remove(taskId);
        taskNotificationVersions.remove(taskId);
        cancel(notificationId);
    }

    private Notification buildCompletedSummaryLocked() {
        boolean allCompleted = pendingTaskIds.isEmpty()
            && failedCount == 0
            && cancelledCount == 0;
        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle()
            .setBigContentTitle(allCompleted ? "全部下载完成" : "下载完成");
        for (String result : recentCompleted) {
            style.addLine(result);
        }
        style.setSummaryText("已完成 " + completedCount + " 个章节");

        return new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle(allCompleted ? "全部下载完成" : "下载完成")
            .setContentText("已完成 " + completedCount + " 个章节")
            .setStyle(style)
            .setContentIntent(createLaunchIntent(NotificationIds.DOWNLOAD_COMPLETED_SUMMARY))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build();
    }

    private Notification buildFailedSummaryLocked() {
        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle()
            .setBigContentTitle("下载失败");
        for (String result : recentFailed) {
            style.addLine(result);
        }
        style.setSummaryText("失败 " + failedCount + " 个章节");

        return new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("下载失败")
            .setContentText("失败 " + failedCount + " 个章节")
            .setStyle(style)
            .setContentIntent(createLaunchIntent(NotificationIds.DOWNLOAD_FAILED_SUMMARY))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build();
    }

    private void addRecentResult(ArrayDeque<String> results, String result) {
        results.addFirst(result);
        while (results.size() > MAX_RECENT_RESULTS) {
            results.removeLast();
        }
    }

    private void clearStaleDownloadNotifications() {
        cancel(NotificationIds.DOWNLOAD_QUEUE_SUMMARY);
        cancel(NotificationIds.DOWNLOAD_COMPLETED_SUMMARY);
        cancel(NotificationIds.DOWNLOAD_FAILED_SUMMARY);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || manager == null) return;

        try {
            for (StatusBarNotification notification : manager.getActiveNotifications()) {
                if (NotificationIds.containsDownloadTask(notification.getId())) {
                    manager.cancel(notification.getId());
                }
            }
        } catch (SecurityException e) {
            Log.d(TAG, "通知权限未授予，跳过清理旧下载通知", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "清理旧下载通知失败", e);
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
        } catch (RuntimeException e) {
            Log.w(TAG, "发布下载通知失败", e);
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
            .setContentIntent(createLaunchIntent(NotificationIds.downloadTask(taskId)))
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

    private String buildResultTitle(String albumTitle, String chapterId,
                                    String chapterTitle) {
        String safeChapterTitle = chapterTitle != null && !chapterTitle.trim().isEmpty()
            ? chapterTitle
            : safeText(chapterId, "章节");
        if (albumTitle == null || albumTitle.trim().isEmpty()) {
            return safeChapterTitle;
        }
        return albumTitle + " · " + safeChapterTitle;
    }

    private String buildIdText(String chapterId) {
        return "ID " + safeText(chapterId, "-");
    }

    private String safeText(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
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
