package io.github.jukomu.platform.logging;

import android.content.Context;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 配置 Android 文件日志并提供当前日志读取能力。 */
public final class ApplicationLogging {
    public static final String LOG_DIRECTORY_PROPERTY = "JQ_VIEWER_LOG_DIR";
    private static final String CURRENT_FILE_NAME = "current.log";
    private static final Duration RETENTION = Duration.ofDays(3);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private ApplicationLogging() {
    }

    public static void initialize(Context context) {
        Context applicationContext = context.getApplicationContext();
        Path logsDirectory = logsDirectory(applicationContext);
        try {
            Files.createDirectories(logsDirectory);
            cleanup(logsDirectory);
            System.setProperty(LOG_DIRECTORY_PROPERTY, logsDirectory.toString());
            if (INITIALIZED.compareAndSet(false, true)) {
                LoggerFactory.getLogger(ApplicationLogging.class).info(
                    "应用日志已初始化: {}", logsDirectory);
                LoggerFactory.getLogger(ApplicationLogging.class).info(
                    "应用启动");
            }
        } catch (IOException error) {
            LoggerFactory.getLogger(ApplicationLogging.class).error(
                "应用日志目录初始化失败: {}", logsDirectory, error);
        }
    }

    public static LogSnapshot readCurrent(Context context) throws IOException {
        Path file = currentLogFile(context.getApplicationContext());
        if (!Files.exists(file)) {
            return new LogSnapshot(file.getFileName().toString(), 0L, "");
        }
        FileTime modifiedAt = Files.getLastModifiedTime(file);
        return new LogSnapshot(
            file.getFileName().toString(),
            modifiedAt.toMillis(),
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    public static Path logsDirectory(Context context) {
        return context.getFilesDir().toPath().resolve("state").resolve("logs");
    }

    private static Path currentLogFile(Context context) {
        return logsDirectory(context).resolve(CURRENT_FILE_NAME);
    }

    private static void cleanup(Path logsDirectory) throws IOException {
        long cutoff = System.currentTimeMillis() - RETENTION.toMillis();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(logsDirectory, "*.log")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file)) continue;
                if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    public record LogSnapshot(String fileName, long updatedAt, String content) {
    }
}
