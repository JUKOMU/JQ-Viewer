package io.github.jukomu.feature.export.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class ArchiveExportTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void plansMergedCbzAndParsesVolumeComicInfo() throws Exception {
        List<File> images = Arrays.asList(
            image("a-1.jpg", "a1"), image("a-2.webp", "a2"), image("b-1.png", "b1"));
        ArchiveExportPlanner.Plan plan = ArchiveExportPlanner.plan(
            "cbz", "merged", "123", "Series", "Alice",
            Arrays.asList(
                new ArchiveExportPlanner.Chapter("10", "First", 1, 2),
                new ArchiveExportPlanner.Chapter("20", "Second", 2, 1)),
            images, 1, 3, 2, 2);

        assertEquals("0001.webp", plan.entries.get(0).name);
        assertEquals("0002.png", plan.entries.get(1).name);
        ComicInfo info = ComicInfoCodec.parse(plan.comicInfo);
        assertEquals("First - Second", info.title);
        assertEquals("1 - 2", info.number);
        assertEquals(Integer.valueOf(2), info.volume);
        assertNull(info.pages.get(0).bookmark);
        assertEquals("Second", info.pages.get(1).bookmark);
    }

    @Test
    public void writesStoredByteIdenticalEntriesAndCleansCancelledTemp() throws Exception {
        File first = image("first.jpg", "original-jpeg-bytes");
        File second = image("second.png", "original-png-bytes");
        ArchiveExportPlanner.Plan plan = ArchiveExportPlanner.plan(
            "cbz", "chapter", "123", "Series", "Alice",
            Arrays.asList(new ArchiveExportPlanner.Chapter("10", "First", 1, 2)),
            Arrays.asList(first, second), 0, 2, 1, 1);
        File output = new File(temporaryFolder.getRoot(), "book.cbz");
        AtomicInteger progress = new AtomicInteger();

        ArchiveVolumeWriter.Report report = new ArchiveVolumeWriter()
            .write(plan, output, progress::set);

        assertEquals(2, progress.get());
        assertEquals(2, report.pageCount);
        try (ZipFile zip = new ZipFile(output)) {
            assertStoredBytes(zip, "0001.jpg", Files.readAllBytes(first.toPath()));
            assertStoredBytes(zip, "0002.png", Files.readAllBytes(second.toPath()));
            assertNotNull(zip.getEntry("ComicInfo.xml"));
        }

        File cancelled = new File(temporaryFolder.getRoot(), "cancelled.cbz");
        assertThrows(Exception.class, () -> new ArchiveVolumeWriter().write(
            plan, cancelled, page -> {
                throw new Exception("cancelled");
            }));
        assertFalse(cancelled.exists());
        assertFalse(ArchiveVolumeWriter.getTempFile(cancelled).exists());
    }

    @Test
    public void plansMergedZipWithChapterDirectoriesAndNoComicInfo() throws Exception {
        ArchiveExportPlanner.Plan plan = ArchiveExportPlanner.plan(
            "zip", "merged", "123", "Series", "Alice",
            Arrays.asList(
                new ArchiveExportPlanner.Chapter("10", "A/B", 1, 2),
                new ArchiveExportPlanner.Chapter("20", "Second", 2, 1)),
            Arrays.asList(image("1.jpg", "1"), image("2.jpeg", "2"), image("3.gif", "3")),
            1, 3, 1, 1);

        assertEquals("001_A_B/0001.jpeg", plan.entries.get(0).name);
        assertEquals("002_Second/0001.gif", plan.entries.get(1).name);
        assertNull(plan.comicInfo);
    }

    private File image(String name, String content) throws Exception {
        File file = new File(temporaryFolder.getRoot(), name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void assertStoredBytes(ZipFile zip, String name, byte[] expected) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertEquals(ZipEntry.STORED, entry.getMethod());
        assertArrayEquals(expected, zip.getInputStream(entry).readAllBytes());
    }
}
