package io.github.jukomu.storage;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载文件存储管理（单例）。
 * <p>
 * 目录结构（私有）：{filesDir}/downloads/{albumId}/{chapterId}/
 * 目录结构（公开）：{PICTURES}/JQViewer/{albumId}/{chapterId}/
 * - meta.json: 备份元数据副本
 * - *.jpg: 图片文件（库 downloadPhoto 直接写入）
 * <p>
 * 维护两个内存索引：
 * - chapterIdToAlbumId: 快速通过 photoId 找到对应 albumId
 * - sortOrderToFilename: 快速通过 (albumId, chapterId, sortOrder) 找到文件名
 */
public class FileStorage {

    private static final String TAG = "FileStorage";
    private static final String META_FILE = "meta.json";

    private static volatile FileStorage instance;

    private File baseDir;
    private final Map<String, String> chapterIdToAlbumId = new HashMap<>();
    private final Map<String, String> sortOrderToFilename = new HashMap<>(); // key: "aid_cid_so"
    private long cachedTotalBytes = 0;
    private boolean sizeNeedsRecalc = true;

    public static synchronized FileStorage getInstance() {
        if (instance == null) {
            instance = new FileStorage();
        }
        return instance;
    }

    private FileStorage() {}

    // ---- 初始化 ----

    public void init(Context context, DownloadDatabase db, boolean usePublicDir) {
        if (usePublicDir) {
            this.baseDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "JQViewer");
        } else {
            this.baseDir = new File(context.getFilesDir(), "downloads");
        }
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        // 从 DB 构建内存索引
        buildIndices(db);
    }

    public File getBaseDir() {
        return baseDir;
    }

    private void buildIndices(DownloadDatabase db) {
        chapterIdToAlbumId.clear();
        sortOrderToFilename.clear();

        List<JSONObject> tasks = db.getAllTasks();
        for (JSONObject task : tasks) {
            try {
                String taskId = task.getString("taskId");
                String albumId = task.getString("albumId");
                String chapterId = task.getString("chapterId");
                String status = task.getString("status");

                if (!"completed".equals(status)) continue;

                chapterIdToAlbumId.put(chapterId, albumId);

                List<JSONObject> images = db.getImages(taskId);
                for (JSONObject img : images) {
                    int sortOrder = img.getInt("sortOrder");
                    String filename = img.getString("filename");
                    String key = makeSortKey(albumId, chapterId, sortOrder);
                    sortOrderToFilename.put(key, filename);
                }
            } catch (Exception ignored) {}
        }
        Log.i(TAG, "Indices built: " + chapterIdToAlbumId.size()
                + " chapters, " + sortOrderToFilename.size() + " images");
    }

    // ---- 目录/文件操作 ----

    File getChapterDir(String albumId, String chapterId) {
        return new File(baseDir, albumId + File.separator + chapterId);
    }

    public File ensureChapterDir(String albumId, String chapterId) {
        File dir = getChapterDir(albumId, chapterId);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public void saveMeta(String albumId, String chapterId, JSONObject metaJson) {
        try {
            File dir = ensureChapterDir(albumId, chapterId);
            File metaFile = new File(dir, META_FILE);
            Files.write(metaFile.toPath(), metaJson.toString(2).getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to save meta.json", e);
        }
    }

    JSONObject readMeta(String albumId, String chapterId) {
        try {
            File metaFile = new File(getChapterDir(albumId, chapterId), META_FILE);
            if (!metaFile.exists()) return null;
            byte[] bytes = Files.readAllBytes(metaFile.toPath());
            return new JSONObject(new String(bytes, "UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to read meta.json", e);
            return null;
        }
    }

    byte[] getImageBytes(String albumId, String chapterId, int sortOrder) {
        String key = makeSortKey(albumId, chapterId, sortOrder);
        String filename = sortOrderToFilename.get(key);
        if (filename == null) return null;

        File imageFile = new File(getChapterDir(albumId, chapterId), filename);
        if (!imageFile.exists()) return null;

        try {
            return Files.readAllBytes(imageFile.toPath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to read image: " + imageFile.getPath(), e);
            return null;
        }
    }

    public byte[] getImageBytesByPhotoId(String photoId, int sortOrder) {
        String albumId = chapterIdToAlbumId.get(photoId);
        if (albumId == null) return null;
        return getImageBytes(albumId, photoId, sortOrder);
    }

    public Integer getFirstImageSortOrder(String albumId, String chapterId) {
        Integer minSo = null;
        String prefix = albumId + "_" + chapterId + "_";
        for (String key : sortOrderToFilename.keySet()) {
            if (key.startsWith(prefix)) {
                try {
                    int so = Integer.parseInt(key.substring(prefix.length()));
                    if (minSo == null || so < minSo) {
                        minSo = so;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return minSo;
    }

    File[] listImageFiles(String albumId, String chapterId) {
        File dir = getChapterDir(albumId, chapterId);
        if (!dir.isDirectory()) return new File[0];
        return dir.listFiles((d, name) -> !name.equals(META_FILE) && !name.endsWith(".tmp"));
    }

    public void deleteChapter(String albumId, String chapterId) {
        File dir = getChapterDir(albumId, chapterId);
        if (dir.isDirectory()) {
            deleteRecursive(dir);
        }
        // 清理内存索引
        chapterIdToAlbumId.remove(chapterId);
        String prefix = albumId + "_" + chapterId + "_";
        sortOrderToFilename.keySet().removeIf(k -> k.startsWith(prefix));
        // 清理父目录（如果为空）
        File parentDir = new File(baseDir, albumId);
        String[] remaining = parentDir.list();
        if (remaining != null && remaining.length == 0) {
            parentDir.delete();
        }
        sizeNeedsRecalc = true;
    }

    public long getTotalUsedBytes() {
        if (sizeNeedsRecalc) {
            cachedTotalBytes = dirSize(baseDir);
            sizeNeedsRecalc = false;
        }
        return cachedTotalBytes;
    }

    // ---- 索引刷新 ----

    public void refreshMappings(String albumId, String chapterId, DownloadDatabase db) {
        List<JSONObject> images = db.getImages(albumId + "_" + chapterId);
        chapterIdToAlbumId.put(chapterId, albumId);
        for (JSONObject img : images) {
            try {
                int sortOrder = img.getInt("sortOrder");
                String filename = img.getString("filename");
                String key = makeSortKey(albumId, chapterId, sortOrder);
                sortOrderToFilename.put(key, filename);
            } catch (Exception ignored) {}
        }
    }

    // ---- 目录搬迁（公开/私有切换） ----

    /**
     * 将所有已下载文件从当前目录搬迁到目标目录。
     * @return 搬迁的文件数，-1 表示无需搬迁
     */
    public int relocate(Context context, boolean usePublicDir) {
        File newDir;
        if (usePublicDir) {
            newDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "JQViewer");
        } else {
            newDir = new File(context.getFilesDir(), "downloads");
        }

        if (baseDir.getAbsolutePath().equals(newDir.getAbsolutePath())) {
            return -1;
        }

        if (!newDir.exists()) {
            newDir.mkdirs();
        }

        int moved = 0;
        File[] children = baseDir.listFiles();
        if (children != null) {
            for (File child : children) {
                File dest = new File(newDir, child.getName());
                if (child.renameTo(dest)) {
                    moved++;
                } else {
                    Log.e(TAG, "Failed to move: " + child.getAbsolutePath());
                }
            }
        }

        baseDir = newDir;
        sizeNeedsRecalc = true;
        Log.i(TAG, "Relocated " + moved + " items to " + (usePublicDir ? "public" : "private"));
        return moved;
    }

    // ---- 空间查询 ----

    public long getAvailableBytes() {
        return baseDir.getFreeSpace();
    }

    // ---- 工具方法 ----

    private static String makeSortKey(String albumId, String chapterId, int sortOrder) {
        return albumId + "_" + chapterId + "_" + sortOrder;
    }

    private static long dirSize(File dir) {
        long size = 0;
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isFile()) {
                size += f.length();
            } else if (f.isDirectory()) {
                size += dirSize(f);
            }
        }
        return size;
    }

    private static void deleteRecursive(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteRecursive(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
