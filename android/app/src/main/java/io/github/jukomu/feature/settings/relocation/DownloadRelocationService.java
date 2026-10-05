package io.github.jukomu.feature.settings.relocation;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.SystemClock;
import io.github.jukomu.feature.download.storage.FileStore;
import io.github.jukomu.platform.persistence.SettingsStore;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 执行下载目录切换所需的复制、校验、清理、检查点和媒体扫描。
 */
public final class DownloadRelocationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadRelocationService.class);
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
        RelocationRun run = new RelocationRun(usePublicDir, oldBaseDir, newDir,
            publicDir, privateDir);
        logStarted(run);

        try {
            if (oldBaseDir != null
                && oldBaseDir.getAbsolutePath().equals(newDir.getAbsolutePath())) {
                LOGGER.info("relocation event=already_active relocationId={} dest={} elapsedMs={}",
                    run.id, run.destination, run.elapsedMs());
                return -1;
            }

            List<File> sourceFiles = new ArrayList<>();
            if (oldBaseDir != null && oldBaseDir.isDirectory()) {
                collectAllFiles(oldBaseDir, sourceFiles);
            }
            int totalFiles = sourceFiles.size();
            run.totalFiles = totalFiles;

            if (totalFiles == 0) {
                LOGGER.info("relocation event=preflight relocationId={} dest={} totalFiles=0 totalBytes=0 availableBytes=unknown batchSize={} matchMode=length elapsedMs={}",
                    run.id,
                    BATCH_SIZE, run.elapsedMs());
                baseDirConsumer.accept(newDir);
                if (!newDir.exists()) {
                    newDir.mkdirs();
                }
                LOGGER.info("relocation event=completed relocationId={} dest={} moved=0 totalFiles=0 resumeIndex=0 scanBatches=0 verification=length sourceDeleteFailures=0 elapsedMs={}",
                    run.id, run.destination, run.elapsedMs());
                return 0;
            }

            long totalSize = 0;
            for (File sourceFile : sourceFiles) {
                totalSize += sourceFile.length();
            }
            if (!newDir.exists()) {
                newDir.mkdirs();
            }
            long availableBytes = fileOperations.availableBytes(newDir);
            run.totalBytes = totalSize;
            run.phase = "preflight";
            LOGGER.info("relocation event=preflight relocationId={} dest={} totalFiles={} totalBytes={} availableBytes={} batchSize={} matchMode=length elapsedMs={}",
                run.id, run.destination, totalFiles, totalSize, availableBytes,
                BATCH_SIZE, run.elapsedMs());
            if (availableBytes < totalSize) {
                run.errorCode = "TARGET_SPACE_INSUFFICIENT";
                throw new IOException(
                    "目标存储空间不足，需要 " + (totalSize / 1024 / 1024) + " MB");
            }

            int startIndex = restoreCheckpoint(sourceFiles, oldBaseDir, newDir, run);
            run.resumeIndex = startIndex;
            int current = startIndex;
            while (current < totalFiles) {
                int batchStart = current;
                int batchEnd = Math.min(batchStart + BATCH_SIZE, totalFiles);
                copyBatch(sourceFiles, batchStart, batchEnd, totalFiles, oldBaseDir, newDir,
                    listener, run);
                verifyBatch(sourceFiles, batchStart, batchEnd, totalFiles, oldBaseDir, newDir,
                    listener, run);
                deleteBatch(sourceFiles, batchStart, batchEnd, totalFiles, oldBaseDir, listener,
                    run);

                current = batchEnd;
                boolean checkpointWritten = writeCheckpoint(usePublicDir, current, totalFiles,
                    run, batchStart, batchEnd);
                LOGGER.info("relocation event=batch_completed relocationId={} batchStart={} batchEnd={} current={} total={} checkpointWrite={} elapsedMs={}",
                    run.id, batchStart, batchEnd, current, totalFiles,
                    checkpointWritten ? "ok" : "failed", run.elapsedMs());
            }

            checkpointConsumer.accept("");
            run.checkpointRetained = "false";
            LOGGER.info("relocation event=checkpoint_clear relocationId={} status=ok elapsedMs={}",
                run.id, run.elapsedMs());
            baseDirConsumer.accept(newDir);
            deleteEmptyDirs(oldBaseDir);

            if (usePublicDir) {
                scanPublicDir(newDir, listener, run);
            }

            LOGGER.info("relocation event=completed relocationId={} dest={} moved={} totalFiles={} resumeIndex={} scanBatches={} verification=length sourceDeleteFailures={} elapsedMs={}",
                run.id, run.destination, current, totalFiles, run.resumeIndex,
                run.scanBatches, run.sourceDeleteFailures, run.elapsedMs());
            return current;
        } catch (IOException error) {
            logFailed(run, error);
            throw error;
        } catch (RuntimeException error) {
            logFailed(run, error);
            throw error;
        }
    }

    private int restoreCheckpoint(List<File> sourceFiles, File oldBaseDir,
                                  File newDir, RelocationRun run) {
        int startIndex = 0;
        String checkpoint = checkpointSupplier.get();
        if (checkpoint == null || checkpoint.isEmpty()) {
            run.checkpointResult = "none";
            run.checkpointRetained = "false";
            LOGGER.info("relocation event=checkpoint_resume relocationId={} checkpointPresent=false resumeIndex=0 resumeResult=none elapsedMs={}",
                run.id, run.elapsedMs());
            return startIndex;
        }

        try {
            JSONObject data = new JSONObject(checkpoint);
            int savedCurrent = data.getInt("current");
            int savedTotal = data.getInt("total");
            String checkpointDest = data.optString("dest", "unknown");
            if (savedTotal != sourceFiles.size()) {
                run.checkpointResult = "rejected";
                run.checkpointRetained = "true";
                LOGGER.warn("relocation event=checkpoint_resume relocationId={} checkpointPresent=true checkpointDest={} checkpointCurrent={} checkpointTotal={} resumeIndex=0 resumeResult=rejected elapsedMs={}",
                    run.id, checkpointDest, savedCurrent, savedTotal, run.elapsedMs());
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
            run.checkpointResult = "accepted";
            run.checkpointRetained = "true";
            LOGGER.info("relocation event=checkpoint_resume relocationId={} checkpointPresent=true checkpointDest={} checkpointCurrent={} checkpointTotal={} resumeIndex={} resumeResult=accepted elapsedMs={}",
                run.id, checkpointDest, savedCurrent, savedTotal, startIndex, run.elapsedMs());
        } catch (Exception error) {
            run.checkpointResult = "corrupt";
            run.checkpointRetained = "true";
            LOGGER.warn("搬迁检查点损坏，从头开始", error);
            LOGGER.warn("relocation event=checkpoint_resume relocationId={} checkpointPresent=true resumeIndex=0 resumeResult=corrupt errorClass={} elapsedMs={}",
                run.id, error.getClass().getSimpleName(), run.elapsedMs());
        }
        return startIndex;
    }

    private void copyBatch(List<File> sourceFiles, int start, int end, int total,
                           File oldBaseDir, File newDir,
                           RelocationEventSink listener, RelocationRun run) throws IOException {
        run.phase = "copying";
        LOGGER.info("relocation event=phase relocationId={} phase=copying batchStart={} batchEnd={} current={} total={} elapsedMs={}",
            run.id, start, end, start, total, run.elapsedMs());
        for (int index = start; index < end; index++) {
            File source = sourceFiles.get(index);
            run.current = index;
            run.currentFile = relativePath(source, oldBaseDir);
            File target = mapToDest(source, newDir, oldBaseDir);
            notifyPhase(listener, index, total, "copying", source, oldBaseDir);
            target.getParentFile().mkdirs();
            try {
                fileOperations.copy(source, target);
            } catch (IOException error) {
                run.errorCode = "COPY_FAILED";
                throw error;
            }
        }
    }

    private void verifyBatch(List<File> sourceFiles, int start, int end, int total,
                             File oldBaseDir, File newDir,
                             RelocationEventSink listener, RelocationRun run) throws IOException {
        run.phase = "verifying";
        LOGGER.info("relocation event=phase relocationId={} phase=verifying batchStart={} batchEnd={} current={} total={} matchMode=length elapsedMs={}",
            run.id, start, end, start, total, run.elapsedMs());
        for (int index = start; index < end; index++) {
            File source = sourceFiles.get(index);
            run.current = index;
            run.currentFile = relativePath(source, oldBaseDir);
            File target = mapToDest(source, newDir, oldBaseDir);
            notifyPhase(listener, index, total, "verifying", source, oldBaseDir);
            if (!fileOperations.isMatchingCopy(source, target)) {
                run.errorCode = "VERIFY_FAILED";
                throw new IOException(
                    "文件校验失败: " + relativePath(source, oldBaseDir));
            }
        }
    }

    private void deleteBatch(List<File> sourceFiles, int start, int end, int total,
                             File oldBaseDir, RelocationEventSink listener, RelocationRun run) {
        run.phase = "deleting";
        LOGGER.info("relocation event=phase relocationId={} phase=deleting batchStart={} batchEnd={} current={} total={} elapsedMs={}",
            run.id, start, end, start, total, run.elapsedMs());
        for (int index = start; index < end; index++) {
            File source = sourceFiles.get(index);
            run.current = index;
            run.currentFile = relativePath(source, oldBaseDir);
            notifyPhase(listener, index, total, "deleting", source, oldBaseDir);
            if (!fileOperations.delete(source)) {
                run.sourceDeleteFailures++;
                LOGGER.warn("relocation event=source_delete_failed relocationId={} phase=deleting relativeFile={} current={} total={} errorCode=SOURCE_DELETE_FAILED elapsedMs={}",
                    run.id, run.currentFile, index, total, run.elapsedMs());
            }
        }
    }

    private boolean writeCheckpoint(boolean usePublicDir, int current, int total,
                                    RelocationRun run, int batchStart, int batchEnd) {
        try {
            JSONObject checkpoint = new JSONObject();
            checkpoint.put("dest", usePublicDir ? "public" : "private");
            checkpoint.put("current", current);
            checkpoint.put("total", total);
            checkpoint.put("startedAt", System.currentTimeMillis());
            checkpointConsumer.accept(checkpoint.toString());
            run.checkpointRetained = "true";
            LOGGER.info("relocation event=checkpoint_write relocationId={} batchStart={} batchEnd={} current={} total={} status=ok elapsedMs={}",
                run.id, batchStart, batchEnd, current, total, run.elapsedMs());
            return true;
        } catch (Exception error) {
            run.checkpointRetained = "unknown";
            LOGGER.warn("relocation event=checkpoint_write relocationId={} batchStart={} batchEnd={} current={} total={} status=failed errorCode=CHECKPOINT_WRITE_FAILED errorClass={} elapsedMs={}",
                run.id, batchStart, batchEnd, current, total,
                error.getClass().getSimpleName(), run.elapsedMs());
            return false;
        }
    }

    private void scanPublicDir(File directory, RelocationEventSink listener, RelocationRun run) {
        List<String> paths = new ArrayList<>();
        collectPaths(directory, paths);

        int total = paths.size();
        for (int index = 0; index < total; index += MEDIA_SCAN_BATCH) {
            int end = Math.min(index + MEDIA_SCAN_BATCH, total);
            String[] batch = paths.subList(index, end).toArray(new String[0]);
            run.phase = "scanning";
            run.scanBatches++;
            LOGGER.info("relocation event=phase relocationId={} phase=scanning batchStart={} batchEnd={} current={} total={} elapsedMs={}",
                run.id, index, end, end, total, run.elapsedMs());
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

    private void logStarted(RelocationRun run) {
        LOGGER.info("relocation event=started relocationId={} dest={} sourceMode={} totalFiles=unknown elapsedMs=0",
            run.id, run.destination, run.sourceMode);
    }

    private void logFailed(RelocationRun run, Exception error) {
        String errorCode = run.errorCode != null ? run.errorCode : "RELOCATION_FAILED";
        LOGGER.error("relocation event=failed relocationId={} dest={} phase={} current={} total={} relativeFile={} errorCode={} errorClass={} errorMessage={} checkpointRetained={} elapsedMs={}",
            run.id, run.destination, run.phase, run.current, run.totalFiles,
            run.currentFile == null ? "" : run.currentFile, errorCode,
            error.getClass().getSimpleName(), sanitizeError(error.getMessage(), run),
            run.checkpointRetained, run.elapsedMs());
    }

    private static String sanitizeError(String message, RelocationRun run) {
        if (message == null) {
            return "";
        }
        String sanitized = message;
        if (!run.sourcePath.isEmpty()) {
            sanitized = sanitized.replace(run.sourcePath, "<source>");
        }
        if (!run.destinationPath.isEmpty()) {
            sanitized = sanitized.replace(run.destinationPath, "<target>");
        }
        return sanitized.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static final class RelocationRun {
        private final String id = UUID.randomUUID().toString();
        private final long startedNanos = SystemClock.elapsedRealtimeNanos();
        private final String destination;
        private final String sourceMode;
        private final String sourcePath;
        private final String destinationPath;
        private String phase = "preflight";
        private String currentFile;
        private String errorCode;
        private String checkpointResult = "none";
        private String checkpointRetained = "unknown";
        private int current;
        private int totalFiles;
        private long totalBytes;
        private int resumeIndex;
        private int sourceDeleteFailures;
        private int scanBatches;

        private RelocationRun(boolean usePublicDir, File oldBaseDir, File newDir,
                              File publicDir, File privateDir) {
            this.destination = usePublicDir ? "public" : "private";
            this.sourceMode = rootMode(oldBaseDir, publicDir, privateDir);
            this.sourcePath = oldBaseDir == null ? "" : oldBaseDir.getAbsolutePath();
            this.destinationPath = newDir.getAbsolutePath();
        }

        private long elapsedMs() {
            return (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L;
        }

        private static String rootMode(File oldBaseDir, File publicDir, File privateDir) {
            if (oldBaseDir == null) {
                return "unknown";
            }
            String oldPath = oldBaseDir.getAbsolutePath();
            if (oldPath.equals(publicDir.getAbsolutePath())) {
                return "public";
            }
            if (oldPath.equals(privateDir.getAbsolutePath())) {
                return "private";
            }
            return "unknown";
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
