package io.github.jukomu.desktop.logging;

import io.github.jukomu.desktop.data.Paths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 配置 Desktop 文件日志并提供当前日志读取能力。
 */
public final class ApplicationLogging {
    private static final String FILE_PREFIX = "jq-viewer-";
    private static final String FILE_SUFFIX = ".log";
    private static final DateTimeFormatter FILE_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss-SSS");
    private static final Duration RETENTION = Duration.ofDays(3);
    private static volatile Path activeLogFile;

    private ApplicationLogging() {
    }

    public static void initialize(Paths paths) throws IOException {
        Objects.requireNonNull(paths, "paths");
        paths.ensureDirectories();
        cleanup(paths.logsDirectory());

        Path currentFile = createStartupLogFile(paths.logsDirectory());
        activeLogFile = currentFile;
        System.setProperty("org.slf4j.simpleLogger.logFile", currentFile.toString());
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "false");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        System.setProperty("org.slf4j.simpleLogger.log.io.github.jukomu.desktop", "debug");
        System.setProperty("org.slf4j.simpleLogger.log.io.github.jukomu.jmcomic", "debug");
    }

    private static Path createStartupLogFile(Path logsDirectory) throws IOException {
        String timestamp = LocalDateTime.now().format(FILE_DATE_FORMAT);
        Path candidate = logsDirectory.resolve(FILE_PREFIX + timestamp + FILE_SUFFIX);
        int suffix = 1;
        while (true) {
            try {
                Files.createFile(candidate);
                return candidate;
            } catch (java.nio.file.FileAlreadyExistsException error) {
                candidate = logsDirectory.resolve(
                    FILE_PREFIX + timestamp + "-" + suffix++ + FILE_SUFFIX);
            }
        }
    }

    public static Path currentLogFile(Paths paths) {
        Path current = activeLogFile;
        if (current != null && current.getParent().equals(paths.logsDirectory())) return current;
        return paths.logsDirectory().resolve(
            FILE_PREFIX + LocalDateTime.now().format(FILE_DATE_FORMAT) + FILE_SUFFIX);
    }

    public static LogSnapshot readCurrent(Paths paths, long fromLine, long fromOffset)
        throws IOException {
        Path file = currentLogFile(paths);
        if (!Files.exists(file)) {
            return new LogSnapshot(file.getFileName().toString(), 0L, 0L, 0L, "", false);
        }
        FileTime modifiedAt = Files.getLastModifiedTime(file);
        long fileSize = Files.size(file);
        if (fromLine <= 0 || fromOffset <= 0 || fromOffset > fileSize) {
            return readTail(file, modifiedAt.toMillis());
        }
        return readFrom(file, modifiedAt.toMillis(), fromLine, fromOffset);
    }

    private static LogSnapshot readTail(Path file, long updatedAt) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            long length = input.length();
            if (length == 0) {
                return new LogSnapshot(file.getFileName().toString(), updatedAt, 0L, 0L, "", true);
            }
            long start = findLastLineStart(input, length);
            long line = countLineBreaks(input, start) + 1;
            boolean terminated = readByte(input, length - 1) == '\n';
            byte[] content = readBytes(input, start, length - start);
            return new LogSnapshot(file.getFileName().toString(), updatedAt,
                terminated ? line + 1L : line, length,
                new String(content, StandardCharsets.UTF_8), true);
        }
    }

    private static LogSnapshot readFrom(Path file, long updatedAt, long fromLine, long fromOffset)
        throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(fromOffset);
            byte[] remaining = readBytes(input, fromOffset, input.length() - fromOffset);
            int completeLength = lastLineBreak(remaining);
            if (completeLength < 0) {
                return new LogSnapshot(file.getFileName().toString(), updatedAt,
                    fromLine, fromOffset, "", false);
            }
            completeLength += 1;
            long nextLine = fromLine + countLineBreaks(remaining, completeLength);
            return new LogSnapshot(file.getFileName().toString(), updatedAt, nextLine,
                fromOffset + completeLength,
                new String(remaining, 0, completeLength, StandardCharsets.UTF_8), false);
        }
    }

    private static long findLastLineStart(RandomAccessFile input, long length) throws IOException {
        long position = length - 1;
        if (readByte(input, position) == '\n') position--;
        while (position >= 0) {
            if (readByte(input, position) == '\n') return position + 1;
            position--;
        }
        return 0L;
    }

    private static long countLineBreaks(RandomAccessFile input, long endExclusive) throws IOException {
        input.seek(0L);
        long count = 0L;
        for (long index = 0; index < endExclusive; index++) {
            if (input.readByte() == '\n') count++;
        }
        return count;
    }

    private static byte[] readBytes(RandomAccessFile input, long start, long length) throws IOException {
        input.seek(start);
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(length, 8192L));
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) break;
            output.write(buffer, 0, count);
            remaining -= count;
        }
        return output.toByteArray();
    }

    private static int lastLineBreak(byte[] bytes) {
        for (int index = bytes.length - 1; index >= 0; index--) {
            if (bytes[index] == '\n') return index;
        }
        return -1;
    }

    private static long countLineBreaks(byte[] bytes, int length) {
        long count = 0L;
        for (int index = 0; index < length; index++) {
            if (bytes[index] == '\n') count++;
        }
        return count;
    }

    private static int readByte(RandomAccessFile input, long position) throws IOException {
        input.seek(position);
        return input.read();
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

    public record LogSnapshot(
        String fileName,
        long updatedAt,
        long nextLine,
        long nextOffset,
        String content,
        boolean reset
    ) {
    }
}
