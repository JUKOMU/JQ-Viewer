package io.github.jukomu.desktop.logging;

import io.github.jukomu.desktop.data.Paths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** 配置 Desktop 文件日志并提供当前日志读取能力。 */
public final class ApplicationLogging {
    private static final String FILE_PREFIX = "jq-viewer-";
    private static final String FILE_SUFFIX = ".log";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Duration RETENTION = Duration.ofDays(3);

    private ApplicationLogging() {
    }

    public static void initialize(Paths paths) throws IOException {
        Objects.requireNonNull(paths, "paths");
        paths.ensureDirectories();
        cleanup(paths.logsDirectory());

        Path currentFile = currentLogFile(paths);
        System.setProperty("org.slf4j.simpleLogger.logFile", currentFile.toString());
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "false");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
    }

    public static Path currentLogFile(Paths paths) {
        return paths.logsDirectory().resolve(
            FILE_PREFIX + LocalDate.now().format(DATE_FORMAT) + FILE_SUFFIX);
    }

    public static LogSnapshot readCurrent(Paths paths) throws IOException {
        Path file = currentLogFile(paths);
        if (!Files.exists(file)) {
            return new LogSnapshot(file.getFileName().toString(), 0L, "");
        }
        FileTime modifiedAt = Files.getLastModifiedTime(file);
        return new LogSnapshot(
            file.getFileName().toString(),
            modifiedAt.toMillis(),
            Files.readString(file, StandardCharsets.UTF_8));
    }

    private static void cleanup(Path logsDirectory) throws IOException {
        long cutoff = System.currentTimeMillis() - RETENTION.toMillis();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(
            logsDirectory, FILE_PREFIX + "*" + FILE_SUFFIX)) {
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
