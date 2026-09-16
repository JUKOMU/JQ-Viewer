package io.github.jukomu.desktop.feature.pdf.render;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/** PDFBox 页面 PNG 的磁盘缓存。 */
public final class PdfPageCache {
    public static final long MAX_BYTES = 128L * 1024L * 1024L;
    private static final Pattern RESOURCE_ID = Pattern.compile("[0-9a-f]{64}");

    private final Path directory;

    public PdfPageCache(Path cacheDirectory) {
        this.directory = cacheDirectory.resolve("pdf-pages").toAbsolutePath().normalize();
        initialize();
    }

    public synchronized Path fileFor(String resourceId) {
        requireResourceId(resourceId);
        return directory.resolve(resourceId + ".png");
    }

    public synchronized boolean contains(String resourceId) {
        return Files.isRegularFile(fileFor(resourceId));
    }

    public synchronized InputStream open(String resourceId) throws IOException {
        Path file = fileFor(resourceId);
        InputStream input = Files.newInputStream(file);
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis()));
        } catch (IOException ignored) {
            // 时间戳只用于容量淘汰顺序。
        }
        return input;
    }

    public synchronized void write(String resourceId, BufferedImage image) throws IOException {
        Path target = fileFor(resourceId);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, resourceId + ".", ".tmp");
        try {
            if (!ImageIO.write(image, "png", temporary.toFile())) {
                throw new IOException("PNG 编码器不可用");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            enforceCapacity();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void clear() {
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("清理 PDF 页面缓存失败", exception);
        }
    }

    public synchronized Stats stats() {
        try {
            Files.createDirectories(directory);
            int entryCount = 0;
            long sizeBytes = 0L;
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path ->
                        path.getFileName().toString().endsWith(".png")).toList()) {
                    entryCount++;
                    sizeBytes = saturatedAdd(sizeBytes, Files.size(file));
                }
            }
            return new Stats(entryCount, sizeBytes);
        } catch (IOException exception) {
            throw new IllegalStateException("读取 PDF 页面缓存状态失败", exception);
        }
    }

    public static boolean isResourceId(String resourceId) {
        return resourceId != null && RESOURCE_ID.matcher(resourceId).matches();
    }

    private void initialize() {
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                        .toList()) {
                    Files.deleteIfExists(file);
                }
            }
            enforceCapacity();
        } catch (IOException exception) {
            throw new IllegalStateException("PDF 页面缓存目录不可用", exception);
        }
    }

    private void enforceCapacity() throws IOException {
        List<Path> pages;
        try (var files = Files.list(directory)) {
            pages = files.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparingLong(PdfPageCache::lastModified)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        }
        long total = 0L;
        for (Path page : pages) total = saturatedAdd(total, Files.size(page));
        for (Path page : pages) {
            if (total <= MAX_BYTES) break;
            long size = Files.size(page);
            if (Files.deleteIfExists(page)) total = Math.max(0L, total - size);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L || left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void requireResourceId(String resourceId) {
        if (!isResourceId(resourceId)) throw new IllegalArgumentException("PDF 页面资源 ID 无效");
    }

    public record Stats(int entryCount, long sizeBytes) {
    }
}
