package io.github.jukomu.feature.export.archive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 以 STORED 条目写入 CBZ/ZIP，图片字节保持不变。 */
public final class ArchiveVolumeWriter {
    private static final int BUFFER_SIZE = 64 * 1024;

    public Report write(ArchiveExportPlanner.Plan plan, File finalFile, Progress progress)
        throws Exception {
        File tempFile = getTempFile(finalFile);
        File parent = tempFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建归档临时目录");
        }
        Files.deleteIfExists(tempFile.toPath());
        try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(
            new FileOutputStream(tempFile)))) {
            int written = 0;
            for (ArchiveExportPlanner.Entry entry : plan.entries) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                writeStored(output, entry.name, entry.source);
                progress.pageWritten(++written);
            }
            if (plan.comicInfo != null) writeStored(output, "ComicInfo.xml", plan.comicInfo);
        } catch (Exception | Error failure) {
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        try {
            validate(tempFile, plan);
            try {
                Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception | Error failure) {
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return new Report(finalFile.length(), plan.pageCount);
    }

    public static File getTempFile(File finalFile) {
        return new File(finalFile.getAbsolutePath() + ".tmp");
    }

    public static File getWorkDirectory(File finalFile) {
        return new File(finalFile.getParentFile(), "." + finalFile.getName() + ".jqarchive-work");
    }

    public static void cleanStaleArtifacts(File finalFile) throws IOException {
        Files.deleteIfExists(getTempFile(finalFile).toPath());
        Files.deleteIfExists(getWorkDirectory(finalFile).toPath());
    }

    private static void writeStored(ZipOutputStream output, String name, File source) throws IOException {
        CRC32 crc = new CRC32();
        long size = checksum(source, crc);
        output.putNextEntry(storedEntry(name, size, crc.getValue()));
        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
        }
        output.closeEntry();
    }

    private static void writeStored(ZipOutputStream output, String name, byte[] content)
        throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content);
        output.putNextEntry(storedEntry(name, content.length, crc.getValue()));
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

    private static long checksum(File source, CRC32 crc) throws IOException {
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                crc.update(buffer, 0, count);
                size += count;
            }
        }
        return size;
    }

    private static void validate(File archive, ArchiveExportPlanner.Plan plan) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            for (ArchiveExportPlanner.Entry expected : plan.entries) {
                ZipEntry entry = zip.getEntry(expected.name);
                if (entry == null || entry.getMethod() != ZipEntry.STORED) {
                    throw new IOException("ARCHIVE_INVALID: 归档图片条目缺失或不是 STORE: "
                        + expected.name);
                }
            }
            if (plan.comicInfo != null) {
                ZipEntry info = zip.getEntry("ComicInfo.xml");
                if (info == null || info.getMethod() != ZipEntry.STORED) {
                    throw new IOException("ARCHIVE_INVALID: ComicInfo.xml 缺失或不是 STORE");
                }
            }
        }
    }

    public interface Progress {
        void pageWritten(int pageCount) throws Exception;
    }

    public static final class Report {
        public final long fileSize;
        public final int pageCount;

        Report(long fileSize, int pageCount) {
            this.fileSize = fileSize;
            this.pageCount = pageCount;
        }
    }
}
