package io.github.jukomu.storage;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
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
    private static final int BATCH_SIZE = 20;
    private static final int MEDIA_SCAN_BATCH = 100;

    /**
     * 文件搬迁进度监听器。
     */
    public interface RelocationListener {
        void onProgress(int current, int total, String phase, String currentFile);
    }

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
     * 将所有已下载文件从当前目录搬迁到目标目录（分批复制→校验→删除）。
     * @param context  Context
     * @param usePublicDir true=公开目录，false=私有目录
     * @param listener  进度回调（每批次完成后触发）
     * @return 搬迁的文件数，-1 表示无需搬迁，0 表示无文件可搬迁
     * @throws IOException 空间不足或文件操作失败时抛出
     */
    public int relocate(Context context, boolean usePublicDir, RelocationListener listener) throws IOException {
        File oldBaseDir = this.baseDir;
        File newDir;
        if (usePublicDir) {
            newDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "JQViewer");
        } else {
            newDir = new File(context.getFilesDir(), "downloads");
        }

        if (oldBaseDir != null && oldBaseDir.getAbsolutePath().equals(newDir.getAbsolutePath())) {
            return -1;
        }

        // 扫描源目录所有文件
        List<File> sourceFiles = new ArrayList<>();
        if (oldBaseDir != null && oldBaseDir.isDirectory()) {
            collectAllFiles(oldBaseDir, sourceFiles);
        }
        int totalFiles = sourceFiles.size();

        if (totalFiles == 0) {
            this.baseDir = newDir;
            if (!newDir.exists()) newDir.mkdirs();
            sizeNeedsRecalc = true;
            return 0;
        }

        // 统计源目录总大小，检查目标空间
        long totalSize = 0;
        for (File f : sourceFiles) totalSize += f.length();
        if (!newDir.exists()) newDir.mkdirs();
        if (newDir.getFreeSpace() < totalSize) {
            throw new IOException("目标存储空间不足，需要 " + (totalSize / 1024 / 1024) + " MB");
        }

        // 检查 checkpoint（中断恢复）
        int startIndex = 0;
        SettingsDatabase settingsDb = SettingsDatabase.getInstance(context);
        String checkpoint = settingsDb.getString("relocation_checkpoint");
        if (checkpoint != null && !checkpoint.isEmpty()) {
            try {
                JSONObject cp = new JSONObject(checkpoint);
                int savedCurrent = cp.getInt("current");
                int savedTotal = cp.getInt("total");
                if (savedTotal == totalFiles) {
                    startIndex = savedCurrent;
                    // 跳过已搬迁完成的文件（目标存在且大小一致）
                    while (startIndex < totalFiles) {
                        File src = sourceFiles.get(startIndex);
                        File dst = mapToDest(src, newDir, oldBaseDir);
                        if (dst.exists() && dst.length() == src.length()) {
                            startIndex++;
                            src.delete(); // 已校验，安全删除源文件
                        } else {
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // checkpoint 损坏，从头开始
            }
        }

        // 分批搬迁
        int current = startIndex;
        while (current < totalFiles) {
            int batchEnd = Math.min(current + BATCH_SIZE, totalFiles);

            // Phase: copying
            for (int i = current; i < batchEnd; i++) {
                File src = sourceFiles.get(i);
                File dst = mapToDest(src, newDir, oldBaseDir);
                notifyPhase(listener, i, totalFiles, "copying", src, oldBaseDir);

                dst.getParentFile().mkdirs();
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
            }

            // Phase: verifying
            for (int i = current; i < batchEnd; i++) {
                File src = sourceFiles.get(i);
                File dst = mapToDest(src, newDir, oldBaseDir);
                notifyPhase(listener, i, totalFiles, "verifying", src, oldBaseDir);

                if (!dst.exists() || dst.length() != src.length()) {
                    throw new IOException("文件校验失败: " + relativePath(src, oldBaseDir));
                }
            }

            // Phase: deleting (副本已验证，安全删除源文件)
            for (int i = current; i < batchEnd; i++) {
                File src = sourceFiles.get(i);
                notifyPhase(listener, i, totalFiles, "deleting", src, oldBaseDir);

                if (!src.delete()) {
                    Log.w(TAG, "Failed to delete source: " + src.getAbsolutePath());
                }
            }

            current = batchEnd;

            // 写 checkpoint
            try {
                JSONObject cp = new JSONObject();
                cp.put("dest", usePublicDir ? "public" : "private");
                cp.put("current", current);
                cp.put("total", totalFiles);
                cp.put("startedAt", System.currentTimeMillis());
                settingsDb.putString("relocation_checkpoint", cp.toString());
            } catch (Exception ignored) {}
        }

        // 全部完成
        settingsDb.putString("relocation_checkpoint", ""); // 清除 checkpoint
        this.baseDir = newDir;
        sizeNeedsRecalc = true;

        // 删除空的旧目录结构
        deleteEmptyDirs(oldBaseDir);

        // 公开模式：分批通知 MediaStore
        if (usePublicDir) {
            scanPublicDir(context, listener);
        }

        Log.i(TAG, "Relocated " + current + " files to " + (usePublicDir ? "public" : "private"));
        return current;
    }

    // ---- 搬迁辅助方法 ----

    /** 递归收集目录下所有文件（含 meta.json） */
    private void collectAllFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                result.add(f);
            } else if (f.isDirectory()) {
                collectAllFiles(f, result);
            }
        }
    }

    /** 将源文件路径映射到目标目录 */
    private File mapToDest(File src, File newBaseDir, File oldBaseDir) {
        return new File(newBaseDir, relativePath(src, oldBaseDir));
    }

    /** 获取文件相对于 base 的路径（不含前导 /） */
    private String relativePath(File file, File base) {
        String absPath = file.getAbsolutePath();
        String basePath = base.getAbsolutePath();
        String rel = absPath.substring(basePath.length());
        if (rel.startsWith(File.separator)) {
            rel = rel.substring(1);
        }
        return rel;
    }

    /** 递归删除空目录 */
    private void deleteEmptyDirs(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteEmptyDirs(child);
                }
            }
        }
        String[] remaining = dir.list();
        if (remaining != null && remaining.length == 0) {
            dir.delete();
        }
    }

    /** MediaStore 分批扫描公开目录 */
    private void scanPublicDir(Context context, RelocationListener listener) {
        List<String> allPaths = new ArrayList<>();
        collectPaths(this.baseDir, allPaths);

        int total = allPaths.size();
        for (int i = 0; i < total; i += MEDIA_SCAN_BATCH) {
            int end = Math.min(i + MEDIA_SCAN_BATCH, total);
            String[] batch = allPaths.subList(i, end).toArray(new String[0]);
            listener.onProgress(end, total, "scanning", null);
            MediaScannerConnection.scanFile(context, batch, null, null);
        }
        listener.onProgress(total, total, "scanning", null);
    }

    /** 递归收集目录下所有绝对路径字符串 */
    private void collectPaths(File dir, List<String> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                result.add(f.getAbsolutePath());
            } else if (f.isDirectory()) {
                collectPaths(f, result);
            }
        }
    }

    /** 安全调用 listener（防御 null） */
    private void notifyPhase(RelocationListener listener, int current, int total,
                             String phase, File file, File oldBaseDir) {
        if (listener != null) {
            listener.onProgress(current, total, phase, relativePath(file, oldBaseDir));
        }
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
