package io.github.jukomu.feature.settings.relocation;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.platform.persistence.SettingsStore;
import org.json.JSONObject;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 执行下载目录切换所需的复制、校验、清理、检查点和媒体扫描。
 */
public final class DownloadRelocationService {

    private static final String TAG = "DownloadRelocation";
    private static final String CHECKPOINT_KEY = "relocation_checkpoint";
    private static final int BATCH_SIZE = 20;
    private static final int MEDIA_SCAN_BATCH = 100;

    private final Context context;
    private final Supplier<File> baseDirSupplier;
    private final Consumer<File> baseDirConsumer;
    private final Supplier<String> checkpointSupplier;
    private final Consumer<String> checkpointConsumer;
    private final File publicDir;
    private final File privateDir;
    private final FileOperations fileOperations;

    public DownloadRelocationService(Context context, SettingsStore settingsStore,
                                     FileStore fileStore) {
        this(
            context,
            fileStore::getBaseDir,
            fileStore::switchBaseDir,
            () -> settingsStore.getString(CHECKPOINT_KEY),
            checkpoint -> settingsStore.putString(CHECKPOINT_KEY, checkpoint),
            new File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES),
                "JQViewer"),
            new File(context.getFilesDir(), "downloads"),
            new DefaultFileOperations());
    }

    DownloadRelocationService(Context context, Supplier<File> baseDirSupplier,
                              Consumer<File> baseDirConsumer,
                              Supplier<String> checkpointSupplier,
                              Consumer<String> checkpointConsumer,
                              File publicDir, File privateDir,
                              FileOperations fileOperations) {
        this.context = context;
        this.baseDirSupplier = baseDirSupplier;
        this.baseDirConsumer = baseDirConsumer;
        this.checkpointSupplier = checkpointSupplier;
        this.checkpointConsumer = checkpointConsumer;
        this.publicDir = publicDir;
        this.privateDir = privateDir;
        this.fileOperations = fileOperations;
    }

    /**
     * 将当前下载目录中的文件分批搬至公开或私有目录。
     *
     * @return 搬迁的文件数，-1 表示目标目录已经生效，0 表示源目录为空
     */
    public int relocate(boolean usePublicDir, RelocationEventSink listener)
        throws IOException {
        File oldBaseDir = baseDirSupplier.get();
        File newDir = usePublicDir ? publicDir : privateDir;

        if (oldBaseDir != null
            && oldBaseDir.getAbsolutePath().equals(newDir.getAbsolutePath())) {
            return -1;
        }

        List<File> sourceFiles = new ArrayList<>();
        if (oldBaseDir != null && oldBaseDir.isDirectory()) {
            collectAllFiles(oldBaseDir, sourceFiles);
        }
        int totalFiles = sourceFiles.size();

        if (totalFiles == 0) {
            baseDirConsumer.accept(newDir);
            if (!newDir.exists()) {
                newDir.mkdirs();
            }
            return 0;
        }

        long totalSize = 0;
        for (File sourceFile : sourceFiles) {
            totalSize += sourceFile.length();
        }
        if (!newDir.exists()) {
            newDir.mkdirs();
        }
        if (fileOperations.availableBytes(newDir) < totalSize) {
            throw new IOException(
                "目标存储空间不足，需要 " + (totalSize / 1024 / 1024) + " MB");
        }

        int startIndex = restoreCheckpoint(sourceFiles, oldBaseDir, newDir);
        int current = startIndex;
        while (current < totalFiles) {
            int batchEnd = Math.min(current + BATCH_SIZE, totalFiles);
            copyBatch(sourceFiles, current, batchEnd, totalFiles, oldBaseDir, newDir,
                listener);
            verifyBatch(sourceFiles, current, batchEnd, totalFiles, oldBaseDir, newDir,
                listener);
            deleteBatch(sourceFiles, current, batchEnd, totalFiles, oldBaseDir, listener);

            current = batchEnd;
            writeCheckpoint(usePublicDir, current, totalFiles);
        }

        checkpointConsumer.accept("");
        baseDirConsumer.accept(newDir);
        deleteEmptyDirs(oldBaseDir);

        if (usePublicDir) {
            scanPublicDir(newDir, listener);
        }

        Log.i(TAG, "Relocated " + current + " files to "
            + (usePublicDir ? "public" : "private"));
        return current;
    }

    private int restoreCheckpoint(List<File> sourceFiles, File oldBaseDir,
                                  File newDir) {
        int startIndex = 0;
        String checkpoint = checkpointSupplier.get();
        if (checkpoint == null || checkpoint.isEmpty()) {
            return startIndex;
        }

        try {
            JSONObject data = new JSONObject(checkpoint);
            int savedCurrent = data.getInt("current");
            int savedTotal = data.getInt("total");
            if (savedTotal != sourceFiles.size()) {
                return startIndex;
            }
            startIndex = savedCurrent;
            while (startIndex < sourceFiles.size()) {
                File source = sourceFiles.get(startIndex);
                File target = mapToDest(source, newDir, oldBaseDir);
                if (!fileOperations.isMatchingCopy(source, target)) {
                    break;
                }
                startIndex++;
                fileOperations.delete(source);
            }
        } catch (Exception error) {
            Log.w(TAG, "搬迁检查点损坏，从头开始", error);
        }
        return startIndex;
    }

    private void copyBatch(List<File> sourceFiles, int start, int end, int total,
                           File oldBaseDir, File newDir,
                           RelocationEventSink listener) throws IOException {
        for (int index = start; index < end; index++) {
            File source = sourceFiles.get(index);
            File target = mapToDest(source, newDir, oldBaseDir);
            notifyPhase(listener, index, total, "copying", source, oldBaseDir);
            target.getParentFile().mkdirs();
            fileOperations.copy(source, target);
        }
    }

    private void verifyBatch(List<File> sourceFiles, int start, int end, int total,
                             File oldBaseDir, File newDir,
                             RelocationEventSink listener) throws IOException {
        for (int index = start; index < end; index++) {
            File source = sourceFiles.get(index);
            File target = mapToDest(source, newDir, oldBaseDir);
            notifyPhase(listener, index, total, "verifying", source, oldBaseDir);
            if (!fileOperations.isMatchingCopy(source, target)) {
                throw new IOException(
                    "文件校验失败: " + relativePath(source, oldBaseDir));
            }
        }
    }

    private void deleteBatch(List<File> sourceFiles, int start, int end, int total,
                             File oldBaseDir, RelocationEventSink listener) {
        for (int index = start; index < end; index++) {
            File source = sourceFiles.get(index);
            notifyPhase(listener, index, total, "deleting", source, oldBaseDir);
            if (!fileOperations.delete(source)) {
                Log.w(TAG, "Failed to delete source: " + source.getAbsolutePath());
            }
        }
    }

    private void writeCheckpoint(boolean usePublicDir, int current, int total) {
        try {
            JSONObject checkpoint = new JSONObject();
            checkpoint.put("dest", usePublicDir ? "public" : "private");
            checkpoint.put("current", current);
            checkpoint.put("total", total);
            checkpoint.put("startedAt", System.currentTimeMillis());
            checkpointConsumer.accept(checkpoint.toString());
        } catch (Exception error) {
            Log.d(TAG, "保存搬迁检查点失败", error);
        }
    }

    private void scanPublicDir(File directory, RelocationEventSink listener) {
        List<String> paths = new ArrayList<>();
        collectPaths(directory, paths);

        int total = paths.size();
        for (int index = 0; index < total; index += MEDIA_SCAN_BATCH) {
            int end = Math.min(index + MEDIA_SCAN_BATCH, total);
            String[] batch = paths.subList(index, end).toArray(new String[0]);
            listener.onRelocationProgress(end, total, "scanning", null);
            fileOperations.scan(context, batch);
        }
        listener.onRelocationProgress(total, total, "scanning", null);
    }

    private static void collectAllFiles(File directory, List<File> result) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                result.add(file);
            } else if (file.isDirectory()) {
                collectAllFiles(file, result);
            }
        }
    }

    private static void collectPaths(File directory, List<String> result) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                result.add(file.getAbsolutePath());
            } else if (file.isDirectory()) {
                collectPaths(file, result);
            }
        }
    }

    private static File mapToDest(File source, File newBaseDir, File oldBaseDir) {
        return new File(newBaseDir, relativePath(source, oldBaseDir));
    }

    private static String relativePath(File file, File base) {
        String relative = file.getAbsolutePath().substring(base.getAbsolutePath().length());
        if (relative.startsWith(File.separator)) {
            relative = relative.substring(1);
        }
        return relative;
    }

    private static void deleteEmptyDirs(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteEmptyDirs(child);
                }
            }
        }
        String[] remaining = directory.list();
        if (remaining != null && remaining.length == 0) {
            directory.delete();
        }
    }

    private static void notifyPhase(RelocationEventSink listener, int current,
                                    int total, String phase, File file,
                                    File oldBaseDir) {
        if (listener != null) {
            listener.onRelocationProgress(
                current, total, phase, relativePath(file, oldBaseDir));
        }
    }

    interface FileOperations {
        long availableBytes(File directory);

        void copy(File source, File target) throws IOException;

        boolean isMatchingCopy(File source, File target);

        boolean delete(File file);

        void scan(Context context, String[] paths);
    }

    private static final class DefaultFileOperations implements FileOperations {

        @Override
        public long availableBytes(File directory) {
            return directory.getFreeSpace();
        }

        @Override
        public void copy(File source, File target) throws IOException {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) > 0) {
                    output.write(buffer, 0, length);
                }
            }
        }

        @Override
        public boolean isMatchingCopy(File source, File target) {
            return target.exists() && target.length() == source.length();
        }

        @Override
        public boolean delete(File file) {
            return file.delete();
        }

        @Override
        public void scan(Context context, String[] paths) {
            MediaScannerConnection.scanFile(context, paths, null, null);
        }
    }
}
