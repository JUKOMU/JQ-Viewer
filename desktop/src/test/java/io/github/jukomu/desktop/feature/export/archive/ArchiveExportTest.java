package io.github.jukomu.desktop.feature.export.archive;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveExportTest {
    @Test
    void plansMergedCbzWithContinuousRootEntriesAndVolumeComicInfo() throws Exception {
        Path root = Files.createTempDirectory("archive-plan-cbz-");
        List<Path> images = List.of(
                image(root, "a-1.jpg", "a1"),
                image(root, "a-2.webp", "a2"),
                image(root, "b-1.png", "b1"));

        ArchiveExportPlanner.Plan plan = ArchiveExportPlanner.plan(
                "cbz", "merged", "123", "Series", "Alice",
                List.of(
                        new ArchiveExportPlanner.Chapter("10", "First", 1, 2),
                        new ArchiveExportPlanner.Chapter("20", "Second", 2, 1)),
                images, 1, 3, 2, 2);

        assertEquals(List.of("0001.webp", "0002.png"),
                plan.entries().stream().map(ArchiveExportPlanner.Entry::name).toList());
        ComicInfo info = ComicInfoCodec.parse(plan.comicInfo());
        assertEquals("First - Second", info.title());
        assertEquals("1 - 2", info.number());
        assertEquals("Series", info.series());
        assertEquals("Alice", info.writer());
        assertEquals("https://18comic.vip/album/123", info.web());
        assertEquals(2, info.volume());
        assertEquals(2, info.count());
        assertEquals(2, info.pageCount());
        assertNull(info.pages().get(0).bookmark());
        assertEquals("Second", info.pages().get(1).bookmark());
    }

    @Test
    void plansMergedZipByChapterAndPreservesOriginalChapterPageNumbers() throws Exception {
        Path root = Files.createTempDirectory("archive-plan-zip-");
        List<Path> images = List.of(
                image(root, "001.jpg", "first"),
                image(root, "002.jpeg", "second"),
                image(root, "003.gif", "third"));

        ArchiveExportPlanner.Plan plan = ArchiveExportPlanner.plan(
                "zip", "merged", "123", "Series", "Alice",
                List.of(
                        new ArchiveExportPlanner.Chapter("10", "A/B", 1, 2),
                        new ArchiveExportPlanner.Chapter("20", "Second", 2, 1)),
                images, 1, 3, 1, 1);

        assertEquals(List.of("001_A_B/0001.jpeg", "002_Second/0001.gif"),
                plan.entries().stream().map(ArchiveExportPlanner.Entry::name).toList());
        assertNull(plan.comicInfo());
    }

    @Test
    void writerStoresByteIdenticalEntriesAndComicInfo() throws Exception {
        Path root = Files.createTempDirectory("archive-writer-");
        Path first = image(root, "first.jpg", "original-jpeg-bytes");
        Path second = image(root, "second.png", "original-png-bytes");
        ArchiveExportPlanner.Plan plan = ArchiveExportPlanner.plan(
                "cbz", "chapter", "123", "Series", "Alice",
                List.of(new ArchiveExportPlanner.Chapter("10", "First", 1, 2)),
                List.of(first, second), 0, 2, 1, 1);
        Path output = root.resolve("book.cbz.tmp");
        AtomicInteger progress = new AtomicInteger();

        ArchiveVolumeWriter.Report report = new ArchiveVolumeWriter()
                .write(plan, output, progress::set);

        assertEquals(2, progress.get());
        assertEquals(2, report.pageCount());
        assertEquals(Files.size(output), report.fileSize());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertStoredBytes(zip, "0001.jpg", Files.readAllBytes(first));
            assertStoredBytes(zip, "0002.png", Files.readAllBytes(second));
            ZipEntry info = zip.getEntry("ComicInfo.xml");
            assertEquals(ZipEntry.STORED, info.getMethod());
            byte[] comicInfo = zip.getInputStream(info).readAllBytes();
            assertFalse(new String(comicInfo, StandardCharsets.UTF_8).contains("<Bookmark>"));
            assertEquals("First", ComicInfoCodec.parse(comicInfo).pages().getFirst().bookmark());
        }
    }

    @Test
    void writerDeletesTemporaryArchiveWhenProgressCancels() throws Exception {
        Path root = Files.createTempDirectory("archive-writer-cancel-");
        ArchiveExportPlanner.Plan plan = ArchiveExportPlanner.plan(
                "zip", "chapter", "123", "Series", "Alice",
                List.of(new ArchiveExportPlanner.Chapter("10", "First", 1, 1)),
                List.of(image(root, "first.jpg", "first")), 0, 1, 1, 1);
        Path temporary = root.resolve("cancelled.zip.tmp");

        assertThrows(Exception.class, () -> new ArchiveVolumeWriter().write(
                plan, temporary, ignored -> { throw new Exception("cancelled"); }));

        assertFalse(Files.exists(temporary));
    }

    private static Path image(Path root, String name, String content) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static void assertStoredBytes(ZipFile zip, String name, byte[] expected) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertEquals(ZipEntry.STORED, entry.getMethod());
        assertArrayEquals(expected, zip.getInputStream(entry).readAllBytes());
    }
}
