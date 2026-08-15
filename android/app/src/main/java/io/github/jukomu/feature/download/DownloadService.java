package io.github.jukomu.feature.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import io.github.jukomu.feature.download.data.DownloadStore;
import io.github.jukomu.feature.download.model.DownloadProgressData;
import io.github.jukomu.feature.download.notification.DownloadForegroundService;
import io.github.jukomu.feature.download.notification.DownloadNotificationHelper;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.feature.download.validation.ImageFileValidator;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.core.client.AbstractJmClient;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.platform.notification.NotificationIds;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 下载服务——任务创建、取消、暂停、恢复、删除、查询。
 * 内部维护 taskIdMap/reverseTaskIdMap/pendingCancel 状态。
 * 纯业务逻辑，不依赖 Capacitor API。
 */
public class DownloadService {

    private static final String TAG = "DownloadService";

    static final String STATUS_QUEUED = "queued";
    static final String STATUS_DOWNLOADING = "downloading";
    static final String STATUS_PAUSED = "paused";
    static final String STATUS_VERIFYING = "verifying";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    private final DownloadStore downloadDb;
    private final FileStore fileStore;
    private final JmApiClient client;
    private final ExecutorService prepareExecutor;
    private volatile DownloadEventSink eventSink;
    private final DownloadNotificationHelper notificationHelper;
    private final Context context;

    /**
     * albumId_chapterId → library taskId
     */
    final Map<String, String> taskIdMap = new ConcurrentHashMap<>();
    /**
     * library taskId → albumId_chapterId
     */
    private final Map<String, String> reverseTaskIdMap = new ConcurrentHashMap<>();
    /**
     * 在 async 块映射 taskId 之前到达的取消请求
     */
    final Set<String> pendingCancel = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();
    private final DownloadForegroundState foregroundState = new DownloadForegroundState();
    private final Object mapLock = new Object();
    private final Map<String, Long> lastNotificationAt = new ConcurrentHashMap<>();

    public DownloadService(DownloadStore downloadDb, FileStore fileStore,
                           JmApiClient client, ExecutorService prepareExecutor,
                           DownloadEventSink eventSink, Context context) {
        this.downloadDb = downloadDb;
        this.fileStore = fileStore;
        this.client = client;
        this.prepareExecutor = prepareExecutor;
        this.eventSink = eventSink;
        this.context = context.getApplicationContext();
        this.notificationHelper = new DownloadNotificationHelper(context.getApplicationContext());
    }

    public void setEventSink(DownloadEventSink eventSink) {
        this.eventSink = eventSink;
    }

    // ---- 下载任务操作 ----

    @SuppressLint("NewApi")
    public String downloadChapter(String albumId, String chapterId,
                                  String albumTitle, String chapterTitle, String coverUrl) {
        FileStore.validateChapterIds(albumId, chapterId);
        String taskId = albumId + "_" + chapterId;

        if (downloadDb.hasActiveOrCompleted(taskId)) {
            String curStatus = downloadDb.getTask(taskId).optString("status");
            if (STATUS_COMPLETED.equals(curStatus)) {
                throw new IllegalStateException("该章节已下载完成");
            } else {
                throw new IllegalStateException("该章节已在下载队列中");
            }
        }

        cancelledTaskIds.remove(taskId);
        downloadDb.insertTask(taskId, albumId, chapterId,
            albumTitle, chapterTitle, coverUrl);
        notificationHelper.registerTask(taskId);
        startForegroundTask(taskId);

        prepareExecutor.submit(() -> {
            try {
                if (downloadDb.getTask(taskId) == null) {
                    cleanupTaskMapping(taskId);
                    cancelNotification(taskId);
                    return;
                }
                JmPhoto photo = client.getPhoto(chapterId);
                List<JmImage> images = photo.getImages();

                downloadDb.insertImages(taskId, images);
                downloadDb.updateTaskDetail(taskId, images.size(),
                    photo.getAuthor(), new JSONArray(photo.getTags()).toString(),
                    photo.getSortOrder(), photo.isSingleAlbum());

                File chapterDir = fileStore.ensureChapterDir(albumId, chapterId);
                fileStore.refreshMappings(albumId, chapterId, downloadDb);

                JSONObject metaJson = new JSONObject();
                metaJson.put("albumId", albumId);
                metaJson.put("chapterId", chapterId);
                metaJson.put("title", chapterTitle);
                metaJson.put("author", photo.getAuthor());
                metaJson.put("tags", new JSONArray(photo.getTags()));
                metaJson.put("totalPages", images.size());
                JSONArray metaImages = new JSONArray();
                for (JmImage img : images) {
                    JSONObject im = new JSONObject();
                    im.put("sortOrder", img.getSortOrder());
                    im.put("filename", img.getFilename());
                    im.put("photoId", img.getPhotoId());
                    metaImages.put(im);
                }
                metaJson.put("images", metaImages);
                fileStore.saveMeta(albumId, chapterId, metaJson);

                Path savePath = photo.isSingleAlbum()
                    ? chapterDir.toPath()
                    : chapterDir.getParentFile().toPath();
                AbstractJmClient abstractClient = (AbstractJmClient) client;
                BaseDownloadTask task = abstractClient.createDownloadTask(photo, savePath);

                String libTaskId = task.getTaskId();
                synchronized (mapLock) {
                    taskIdMap.put(taskId, libTaskId);
                    reverseTaskIdMap.put(libTaskId, taskId);
                }

                task.addObserver(new DownloadObserver(taskId, albumId, chapterId,
                    images.size(), downloadDb, fileStore, this));

                downloadDb.updateStatus(taskId, STATUS_DOWNLOADING);
                notifyProgress(taskId, albumId, chapterId, 0, images.size(),
                    STATUS_DOWNLOADING, null);

                if (pendingCancel.remove(taskId)) {
                    fileStore.deleteChapter(albumId, chapterId);
                    downloadDb.deleteImages(taskId);
                    downloadDb.deleteTask(taskId);
                    cleanupTaskMapping(taskId);
                    cancelNotification(taskId);
                    return;
                }

                abstractClient.downloadManager().submit(task);
                if (pendingCancel.remove(taskId)) {
                    abstractClient.downloadManager().cancel(libTaskId);
                }
            } catch (Exception e) {
                downloadDb.updateFailed(taskId, 0, e.getMessage());
                showFailedNotification(taskId, albumTitle, chapterId,
                    chapterTitle, e.getMessage());
                notifyProgress(taskId, albumId, chapterId, 0, 0,
                    STATUS_FAILED, e.getMessage());
                cleanupTaskMapping(taskId);
            }
        });

        return taskId;
    }

    public JSONArray getDownloadTasks() {
        JSONArray arr = new JSONArray();
        List<JSONObject> tasks = downloadDb.getAllTasks();
        for (JSONObject task : tasks) {
            try {
                arr.put(task);
            } catch (Exception e) {
                Log.d(TAG, "跳过无效下载任务条目", e);
            }
        }
        return arr;
    }

    public long getUsedBytes() {
        return fileStore.getTotalUsedBytes();
    }

    public long getAvailableBytes() {
        return fileStore.getAvailableBytes();
    }

    public void cancelDownload(String taskId) {
        JSONObject task = downloadDb.getTask(taskId);
        if (task == null) return;

        String status = task.optString("status");

        if (STATUS_QUEUED.equals(status)) {
            pendingCancel.remove(taskId);
            completeCancel(taskId, task);
        } else if (STATUS_PAUSED.equals(status)) {
            String libTaskId = taskIdMap.get(taskId);
            if (libTaskId != null) {
                try {
                    ((AbstractJmClient) client).downloadManager().cancel(libTaskId);
                } catch (Exception e) {
                    Log.d(TAG, "取消已暂停任务的底层下载失败，继续清理本地任务", e);
                }
            }
            completeCancel(taskId, task);
        } else if (STATUS_DOWNLOADING.equals(status)) {
            pendingCancel.add(taskId);
            String libTaskId = taskIdMap.get(taskId);
            if (libTaskId != null) {
                try {
                    ((AbstractJmClient) client).downloadManager().cancel(libTaskId);
                } catch (Exception e) {
                    Log.d(TAG, "取消下载中的底层任务失败，继续清理本地任务", e);
                }
            }
            completeCancel(taskId, task);
        } else if (STATUS_VERIFYING.equals(status)) {
            throw new IllegalStateException("校验中的任务不能取消");
        } else {
            completeCancel(taskId, task);
        }
    }

    boolean isCancelled(String taskId) {
        return cancelledTaskIds.contains(taskId) || downloadDb.getTask(taskId) == null;
    }

    public void pauseDownload(String taskId) {
        JSONObject task = downloadDb.getTask(taskId);
        if (task == null) throw new IllegalArgumentException("Task not found");

        String status = task.optString("status");
        if (!STATUS_DOWNLOADING.equals(status))
            throw new IllegalStateException("只有下载中的任务可以暂停");

        String libTaskId = taskIdMap.get(taskId);
        if (libTaskId == null) throw new IllegalStateException("Library task not found");

        AbstractJmClient ac = (AbstractJmClient) client;
        if (ac.downloadManager().getTask(libTaskId) == null)
            throw new IllegalStateException("Task not found in download manager");

        ac.downloadManager().pause(libTaskId);
        downloadDb.updateStatus(taskId, STATUS_PAUSED);
        stopForegroundTask(taskId);
        showPausedNotification(taskId, task.optString("albumTitle"),
            task.optString("chapterId"), task.optString("chapterTitle"),
            task.optString("coverUrl"),
            task.optInt("downloadedPages"), task.optInt("totalPages"));
        notifyProgress(taskId, task.optString("albumId"), task.optString("chapterId"),
            task.optInt("downloadedPages"), task.optInt("totalPages"),
            STATUS_PAUSED, null);
    }

    public void resumeDownload(String taskId) {
        JSONObject task = downloadDb.getTask(taskId);
        if (task == null) throw new IllegalArgumentException("Task not found");

        String status = task.optString("status");
        if (!STATUS_PAUSED.equals(status))
            throw new IllegalStateException("只有已暂停的任务可以继续");

        String libTaskId = taskIdMap.get(taskId);
        if (libTaskId == null) throw new IllegalStateException("Library task not found");

        AbstractJmClient ac = (AbstractJmClient) client;
        if (ac.downloadManager().getTask(libTaskId) == null)
            throw new IllegalStateException("Task not found in download manager");

        ac.downloadManager().resume(libTaskId);
        downloadDb.updateStatus(taskId, STATUS_DOWNLOADING);
        startForegroundTask(taskId);
        showDownloadNotification(taskId, task.optString("albumTitle"),
            task.optString("chapterId"), task.optString("chapterTitle"),
            task.optString("coverUrl"),
            task.optInt("downloadedPages"), task.optInt("totalPages"), true);
        notifyProgress(taskId, task.optString("albumId"), task.optString("chapterId"),
            task.optInt("downloadedPages"), task.optInt("totalPages"),
            STATUS_DOWNLOADING, null);
    }

    public void deleteDownloaded(String albumId, String chapterId) {
        String taskId = albumId + "_" + chapterId;
        pendingCancel.remove(taskId);

        String libTaskId = taskIdMap.get(taskId);
        if (libTaskId != null) {
            ((AbstractJmClient) client).downloadManager().cancel(libTaskId);
        }

        fileStore.deleteChapter(albumId, chapterId);
        downloadDb.deleteImages(taskId);
        downloadDb.deleteTask(taskId);
        cleanupTaskMapping(taskId);
        cancelNotification(taskId);
    }

    public JSONObject getDownloadedPhoto(String albumId, String chapterId) {
        String taskId = albumId + "_" + chapterId;
        JSONObject task = downloadDb.getTask(taskId);
        if (task == null) throw new IllegalArgumentException("Task not found");

        String status = task.optString("status");
        if (!STATUS_COMPLETED.equals(status))
            throw new IllegalStateException("Task is not completed");

        List<JSONObject> images = downloadDb.getImages(taskId);
        JSONObject ret = new JSONObject();
        try {
            ret.put("id", chapterId);
            ret.put("title", task.optString("chapterTitle"));
            ret.put("albumId", albumId);
            ret.put("sortOrder", 0);
            ret.put("author", task.optString("author", ""));
            ret.put("tags", new JSONArray());

            JSONArray imageArray = new JSONArray();
            for (JSONObject img : images) {
                JSONObject imgObj = new JSONObject();
                imgObj.put("photoId", img.optString("photoId"));
                imgObj.put("scrambleId", img.optString("scrambleId"));
                imgObj.put("filename", img.optString("filename"));
                imgObj.put("url", img.optString("url"));
                imgObj.put("queryParams", img.optString("queryParams", ""));
                imgObj.put("sortOrder", img.optInt("sortOrder"));
                imageArray.put(imgObj);
            }
            ret.put("images", imageArray);
        } catch (Exception e) {
            Log.w(TAG, "构建已下载章节信息失败", e);
        }
        return ret;
    }

    // ---- 内部工具（DownloadObserver 通过 package-private 访问） ----

    void cleanupTaskMapping(String ourTaskId) {
        synchronized (mapLock) {
            String libTaskId = taskIdMap.remove(ourTaskId);
            if (libTaskId != null) {
                reverseTaskIdMap.remove(libTaskId);
            }
        }
        lastNotificationAt.remove(ourTaskId);
        stopForegroundTask(ourTaskId);
    }

    void updateDownloadNotification(String taskId, int downloadedPages, int totalPages,
                                    String status, String error) {
        JSONObject task = downloadDb.getTask(taskId);
        String chapterTitle = task != null ? task.optString("chapterTitle", taskId) : taskId;
        String albumTitle = task != null ? task.optString("albumTitle", chapterTitle) : chapterTitle;
        String chapterId = task != null ? task.optString("chapterId", taskId) : taskId;
        String coverUrl = task != null ? task.optString("coverUrl", "") : "";

        if (STATUS_DOWNLOADING.equals(status)) {
            startForegroundTask(taskId);
            showDownloadNotification(taskId, albumTitle, chapterId, chapterTitle, coverUrl,
                downloadedPages, totalPages, false);
        } else if (STATUS_PAUSED.equals(status)) {
            stopForegroundTask(taskId);
            showPausedNotification(taskId, albumTitle, chapterId, chapterTitle, coverUrl,
                downloadedPages, totalPages);
        } else if (STATUS_VERIFYING.equals(status)) {
            notificationHelper.showVerifying(notificationId(taskId), taskId, albumTitle,
                chapterId, chapterTitle, coverUrl);
        } else if (STATUS_COMPLETED.equals(status)) {
            showCompletedNotification(taskId, albumTitle, chapterId, chapterTitle);
        } else if (STATUS_FAILED.equals(status)) {
            if ("已取消".equals(error)) {
                cancelNotification(taskId);
            } else {
                showFailedNotification(taskId, albumTitle, chapterId, chapterTitle, error);
            }
        }
    }

    long calcChapterFileSize(String albumId, String chapterId) {
        File chapterDir = fileStore.getChapterDir(albumId, chapterId);
        if (chapterDir == null || !chapterDir.isDirectory()) return 0;
        File[] files = chapterDir.listFiles(
            (dir, name) -> !name.equals("meta.json") && !name.endsWith(".tmp"));
        if (files == null) return 0;
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        return total;
    }

    // ---- 内部 ----

    private void notifyProgress(String taskId, String albumId, String chapterId,
                                int downloadedPages, int totalPages,
                                String status, String error) {
        notifyDownloadProgress(taskId, albumId, chapterId, downloadedPages, totalPages,
            status, error, 0, 0, 0);
    }

    void notifyDownloadProgress(String taskId, String albumId, String chapterId,
                                int downloadedPages, int totalPages, String status,
                                String error, long speed, long totalSize,
                                long downloadedBytes) {
        DownloadEventSink current = eventSink;
        if (current != null) {
            current.onDownloadProgress(new DownloadProgressData(
                taskId, albumId, chapterId, downloadedPages, totalPages,
                status, error, speed, totalSize, downloadedBytes));
        }
    }

    void finishDownloadWithValidation(String taskId, String albumId,
                                      String chapterId, int totalImages) {
        if (isCancelled(taskId)) return;

        downloadDb.updateProgress(taskId, totalImages);
        downloadDb.updateStatus(taskId, STATUS_VERIFYING);
        notifyProgress(taskId, albumId, chapterId, totalImages, totalImages,
            STATUS_VERIFYING, null);
        updateDownloadNotification(taskId, totalImages, totalImages,
            STATUS_VERIFYING, null);

        ChapterValidation result = validateCompletedDownload(
            taskId, albumId, chapterId, totalImages);

        if (isCancelled(taskId)) return;

        long totalSize = calcChapterFileSize(albumId, chapterId);
        downloadDb.updateSize(taskId, totalSize);
        if (!result.valid) {
            downloadDb.updateFailed(taskId, result.verifiedPages, result.error);
            updateDownloadNotification(taskId, result.verifiedPages,
                totalImages, STATUS_FAILED, result.error);
            notifyProgress(taskId, albumId, chapterId, result.verifiedPages,
                totalImages, STATUS_FAILED, result.error, 0, totalSize);
            cleanupTaskMapping(taskId);
            return;
        }

        Integer firstSortOrder = fileStore.getFirstImageSortOrder(albumId, chapterId);
        downloadDb.updateCompleted(taskId, totalImages,
            firstSortOrder != null ? firstSortOrder : 1);
        updateDownloadNotification(taskId, totalImages,
            totalImages, STATUS_COMPLETED, null);
        notifyProgress(taskId, albumId, chapterId, totalImages,
            totalImages, STATUS_COMPLETED, null, 0, totalSize);
        cleanupTaskMapping(taskId);
    }

    private ChapterValidation validateCompletedDownload(String taskId,
                                                        String albumId,
                                                        String chapterId,
                                                        int totalImages) {
        JSONObject meta;
        try {
            meta = fileStore.readMeta(albumId, chapterId);
        } catch (FileNotFoundException error) {
            return ChapterValidation.failure(0, "meta.json 缺失");
        } catch (org.json.JSONException error) {
            return ChapterValidation.failure(0, "meta.json 内容无效");
        } catch (java.io.IOException error) {
            Log.e(TAG, "meta.json 读取失败: " + taskId, error);
            return ChapterValidation.failure(0, "meta.json 读取失败");
        }

        JSONObject databaseTask = downloadDb.getTask(taskId);
        if (databaseTask == null
            || !albumId.equals(requiredString(databaseTask, "albumId"))
            || !chapterId.equals(requiredString(databaseTask, "chapterId"))
            || databaseTask.optInt("totalPages", -1) != totalImages) {
            return ChapterValidation.failure(0, "DB 与 meta.json 记录不一致");
        }

        List<JSONObject> databaseImages = downloadDb.getImages(taskId);
        JSONArray metaImages = meta.optJSONArray("images");
        if (!sameImageManifest(albumId, chapterId, totalImages,
            databaseImages, meta, metaImages)) {
            return ChapterValidation.failure(0, "DB 与 meta.json 记录不一致");
        }

        int verifiedPages = 0;
        boolean allValid = true;
        for (JSONObject image : databaseImages) {
            String filename = image.optString("filename", "");
            File imageFile = fileStore.getExpectedImageFile(
                albumId, chapterId, filename);

            try {
                if (ImageFileValidator.validateFull(imageFile)) {
                    verifiedPages++;
                    continue;
                }

                allValid = false;
                if (imageFile != null && imageFile.isFile() && !imageFile.delete()) {
                    Log.w(TAG, "校验失败图片删除失败: " + imageFile.getPath());
                }
            } catch (OutOfMemoryError error) {
                Log.e(TAG, "图片完整校验资源不足: " + filename, error);
                return ChapterValidation.failure(verifiedPages, "图片校验资源不足");
            }
        }

        return allValid
            ? ChapterValidation.success(verifiedPages)
            : ChapterValidation.failure(verifiedPages, "至少一张图片不可用");
    }

    private boolean sameImageManifest(String albumId, String chapterId,
                                      int totalImages, List<JSONObject> databaseImages,
                                      JSONObject meta, JSONArray metaImages) {
        if (databaseImages == null || metaImages == null
            || databaseImages.size() != totalImages
            || metaImages.length() != totalImages
            || metaImages.length() != databaseImages.size()
            || !albumId.equals(requiredString(meta, "albumId"))
            || !chapterId.equals(requiredString(meta, "chapterId"))
            || meta.optInt("totalPages", -1) != totalImages) {
            return false;
        }

        Map<Integer, JSONObject> databaseBySortOrder = new HashMap<>();
        for (JSONObject image : databaseImages) {
            String filename = requiredString(image, "filename");
            String photoId = requiredString(image, "photoId");
            if (filename == null || photoId == null) return false;

            int sortOrder = image.optInt("sortOrder", Integer.MIN_VALUE);
            if (sortOrder == Integer.MIN_VALUE
                || databaseBySortOrder.put(sortOrder, image) != null) {
                return false;
            }
        }

        Set<Integer> seenSortOrders = new HashSet<>();
        for (int index = 0; index < metaImages.length(); index++) {
            JSONObject image = metaImages.optJSONObject(index);
            if (image == null) return false;

            String filename = requiredString(image, "filename");
            String photoId = requiredString(image, "photoId");
            int sortOrder = image.optInt("sortOrder", Integer.MIN_VALUE);
            if (filename == null || photoId == null
                || sortOrder == Integer.MIN_VALUE
                || !seenSortOrders.add(sortOrder)) {
                return false;
            }

            JSONObject databaseImage = databaseBySortOrder.get(sortOrder);
            if (databaseImage == null
                || !filename.equals(requiredString(databaseImage, "filename"))
                || !photoId.equals(requiredString(databaseImage, "photoId"))) {
                return false;
            }
        }
        return seenSortOrders.size() == databaseBySortOrder.size();
    }

    private String requiredString(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        String value = object.optString(key, null);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static final class ChapterValidation {
        private final boolean valid;
        private final int verifiedPages;
        private final String error;

        private ChapterValidation(boolean valid, int verifiedPages, String error) {
            this.valid = valid;
            this.verifiedPages = verifiedPages;
            this.error = error;
        }

        private static ChapterValidation success(int pages) {
            return new ChapterValidation(true, pages, null);
        }

        private static ChapterValidation failure(int pages, String error) {
            return new ChapterValidation(false, pages, error);
        }
    }

    private void completeCancel(String taskId, JSONObject task) {
        cancelledTaskIds.add(taskId);
        pendingCancel.remove(taskId);
        String albumId = task.optString("albumId");
        String chapterId = task.optString("chapterId");
        fileStore.deleteChapter(albumId, chapterId);
        downloadDb.deleteImages(taskId);
        downloadDb.deleteTask(taskId);
        cancelNotification(taskId);
        notifyProgress(taskId, albumId, chapterId, 0,
            task.optInt("totalPages"), "cancelled", "已取消");
        cleanupTaskMapping(taskId);
    }

    private void notifyProgress(String taskId, String albumId, String chapterId,
                                int downloadedPages, int totalPages,
                                String status, String error, long speed, long totalSize) {
        notifyDownloadProgress(taskId, albumId, chapterId, downloadedPages, totalPages,
            status, error, speed, totalSize, 0);
    }

    private int notificationId(String taskId) {
        return NotificationIds.downloadTask(taskId);
    }

    private void showDownloadNotification(String taskId, String albumTitle,
                                          String chapterId, String chapterTitle,
                                          String coverUrl,
                                          int downloadedPages, int totalPages, boolean force) {
        long now = System.currentTimeMillis();
        Long last = lastNotificationAt.get(taskId);
        if (!force && last != null && now - last < 1000) return;
        lastNotificationAt.put(taskId, now);
        notificationHelper.showProgress(notificationId(taskId), taskId, albumTitle,
            chapterId, chapterTitle, coverUrl, downloadedPages, totalPages);
    }

    private void showPausedNotification(String taskId, String albumTitle,
                                        String chapterId, String chapterTitle,
                                        String coverUrl,
                                        int downloadedPages, int totalPages) {
        notificationHelper.showPaused(notificationId(taskId), taskId, albumTitle,
            chapterId, chapterTitle, coverUrl, downloadedPages, totalPages);
    }

    private void showCompletedNotification(String taskId, String albumTitle,
                                           String chapterId, String chapterTitle) {
        lastNotificationAt.remove(taskId);
        notificationHelper.showComplete(notificationId(taskId), taskId,
            albumTitle, chapterId, chapterTitle);
    }

    private void showFailedNotification(String taskId, String albumTitle,
                                        String chapterId, String chapterTitle,
                                        String error) {
        lastNotificationAt.remove(taskId);
        notificationHelper.showError(notificationId(taskId), taskId,
            albumTitle, chapterId, chapterTitle, error);
    }

    private void cancelNotification(String taskId) {
        lastNotificationAt.remove(taskId);
        notificationHelper.cancelTask(notificationId(taskId), taskId);
    }

    private void startForegroundTask(String taskId) {
        foregroundState.start(taskId, this::updateForegroundService);
    }

    private void stopForegroundTask(String taskId) {
        foregroundState.stop(taskId, this::updateForegroundService);
    }

    private void updateForegroundService(DownloadForegroundState.Snapshot snapshot) {
        DownloadForegroundService.update(
            context,
            snapshot.activeCount,
            snapshot.revision
        );
    }
}
