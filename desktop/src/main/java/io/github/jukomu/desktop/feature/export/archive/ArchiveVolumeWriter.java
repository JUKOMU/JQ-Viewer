package io.github.jukomu.desktop.feature.export.archive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 以 STORED 条目写入 CBZ/ZIP，图片字节保持不变。
 */
public final class ArchiveVolumeWriter {
    private static final int BUFFER_SIZE = 64 * 1024;

    public Report write(ArchiveExportPlanner.Plan plan, Path temporaryFile, Progress progress)
        throws Exception {
        Files.createDirectories(temporaryFile.getParent());
        Files.deleteIfExists(temporaryFile);
        try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(
            Files.newOutputStream(temporaryFile)))) {
            int written = 0;
            for (ArchiveExportPlanner.Entry entry : plan.entries()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                writeStored(output, entry.name(), entry.source());
                progress.pageWritten(++written);
            }
            if (plan.comicInfo() != null) {
                writeStored(output, "ComicInfo.xml", plan.comicInfo());
            }
        } catch (Exception | Error failure) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        try {
            validate(temporaryFile, plan);
            return new Report(Files.size(temporaryFile), plan.pageCount());
        } catch (Exception | Error failure) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void writeStored(ZipOutputStream output, String name, Path source) throws IOException {
        CRC32 crc = new CRC32();
        long size = checksum(source, crc);
        ZipEntry entry = storedEntry(name, size, crc.getValue());
        output.putNextEntry(entry);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static void writeStored(ZipOutputStream output, String name, byte[] content) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry entry = storedEntry(name, content.length, crc.getValue());
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    private static ZipEntry storedEntry(String name, long size, long crc) {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc);
        return entry;
    }

    private static long checksum(Path source, CRC32 crc) throws IOException {
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                crc.update(buffer, 0, count);
                size += count;
            }
        }
        return size;
    }

    private static void validate(Path archive, ArchiveExportPlanner.Plan plan) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ArchiveExportPlanner.Entry expected : plan.entries()) {
                ZipEntry entry = zip.getEntry(expected.name());
                if (entry == null || entry.getMethod() != ZipEntry.STORED) {
                    throw new IOException("ARCHIVE_INVALID: 归档图片条目缺失或不是 STORE: "
                        + expected.name());
                }
            }
            if (plan.comicInfo() != null) {
                ZipEntry info = zip.getEntry("ComicInfo.xml");
                if (info == null || info.getMethod() != ZipEntry.STORED) {
                    throw new IOException("ARCHIVE_INVALID: ComicInfo.xml 缺失或不是 STORE");
                }
            }
        }
    }

    @FunctionalInterface
    public interface Progress {
        void pageWritten(int pageCount) throws Exception;
    }

    public record Report(long fileSize, int pageCount) {
    }
}
