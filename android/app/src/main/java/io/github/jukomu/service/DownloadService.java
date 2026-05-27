package io.github.jukomu.service;

import android.annotation.SuppressLint;
import android.util.Log;

import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.core.client.AbstractJmClient;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    static final String STATUS_COMPLETED = "completed";
    static final String STATUS_FAILED = "failed";

    private final DownloadStore downloadDb;
    private final FileStore fileStore;
    private final JmApiClient client;
    private final ExecutorService imageExecutor;
    private final ServiceListener listener;

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
    private final Object mapLock = new Object();

    public DownloadService(DownloadStore downloadDb, FileStore fileStore,
                           JmApiClient client, ExecutorService imageExecutor,
                           ServiceListener listener) {
        this.downloadDb = downloadDb;
        this.fileStore = fileStore;
        this.client = client;
        this.imageExecutor = imageExecutor;
        this.listener = listener;
    }

    // ---- 下载任务操作 ----

    @SuppressLint("NewApi")
    public String downloadChapter(String albumId, String chapterId,
                                  String albumTitle, String chapterTitle, String coverUrl) {
        String taskId = albumId + "_" + chapterId;

        if (downloadDb.hasActiveOrCompleted(taskId)) {
            String curStatus = downloadDb.getTask(taskId).optString("status");
            if (STATUS_COMPLETED.equals(curStatus)) {
                throw new IllegalStateException("该章节已下载完成");
            } else {
                throw new IllegalStateException("该章节已在下载队列中");
            }
        }

        downloadDb.insertTask(taskId, albumId, chapterId,
            albumTitle, chapterTitle, coverUrl);

        imageExecutor.submit(() -> {
            try {
                JmPhoto photo = client.getPhoto(chapterId);
                List<JmImage> images = photo.getImages();

                downloadDb.insertImages(taskId, images);
                downloadDb.updateTaskDetail(taskId, images.size(),
                    photo.getAuthor(), new JSONArray(photo.getTags()).toString(),
                    photo.getSortOrder());

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
                    images.size(), downloadDb, fileStore, listener, this));

                downloadDb.updateStatus(taskId, STATUS_DOWNLOADING);
                notifyProgress(taskId, albumId, chapterId, 0, images.size(),
                    STATUS_DOWNLOADING, null);

                if (pendingCancel.remove(taskId)) {
                    fileStore.deleteChapter(albumId, chapterId);
                    downloadDb.deleteImages(taskId);
                    downloadDb.deleteTask(taskId);
                    cleanupTaskMapping(taskId);
                    return;
                }

                abstractClient.downloadManager().submit(task);
                if (pendingCancel.remove(taskId)) {
                    abstractClient.downloadManager().cancel(libTaskId);
                }
            } catch (Exception e) {
                downloadDb.updateFailed(taskId, 0, e.getMessage());
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
            downloadDb.deleteImages(taskId);
            downloadDb.deleteTask(taskId);
            cleanupTaskMapping(taskId);
        } else if (STATUS_PAUSED.equals(status)) {
            String libTaskId = taskIdMap.get(taskId);
            if (libTaskId != null) {
                ((AbstractJmClient) client).downloadManager().cancel(libTaskId);
            } else {
                String albumId = task.optString("albumId");
                String chapterId = task.optString("chapterId");
                fileStore.deleteChapter(albumId, chapterId);
                downloadDb.deleteImages(taskId);
                downloadDb.deleteTask(taskId);
                cleanupTaskMapping(taskId);
            }
        } else if (STATUS_DOWNLOADING.equals(status)) {
            String libTaskId = taskIdMap.get(taskId);
            if (libTaskId != null) {
                ((AbstractJmClient) client).downloadManager().cancel(libTaskId);
            } else {
                pendingCancel.add(taskId);
            }
        } else {
            String albumId = task.optString("albumId");
            String chapterId = task.optString("chapterId");
            fileStore.deleteChapter(albumId, chapterId);
            downloadDb.deleteImages(taskId);
            downloadDb.deleteTask(taskId);
            cleanupTaskMapping(taskId);
        }
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
        if (listener != null) {
            listener.onDownloadProgress(new DownloadProgressData(
                taskId, albumId, chapterId, downloadedPages, totalPages,
                status, error, 0, 0));
        }
    }

    private void notifyProgress(String taskId, String albumId, String chapterId,
                                int downloadedPages, int totalPages,
                                String status, String error, long speed, long totalSize) {
        if (listener != null) {
            listener.onDownloadProgress(new DownloadProgressData(
                taskId, albumId, chapterId, downloadedPages, totalPages,
                status, error, speed, totalSize));
        }
    }
}
