package io.github.jukomu.service;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PdfExportJobValidatorTest {

    @Test
    public void validatesChapterJob() {
        PdfExportService.ExportJob job = chapterJob("101");

        PdfExportJobValidator.validate(job);

        assertEquals("chapter:100:101", PdfExportJobValidator.taskKey(job));
        assertEquals(Arrays.asList("100:101"),
            PdfExportJobValidator.chapterResourceKeys(job));
    }

    @Test
    public void validatesMergedJobAndBuildsOrderedKeys() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("100", "102", 2),
            chapter("100", "109", 0),
            chapter("100", "103", 3));

        PdfExportJobValidator.validate(job);

        assertEquals("merged:100:102,109,103",
            PdfExportJobValidator.taskKey(job));
        assertEquals(Arrays.asList("100:102", "100:109", "100:103"),
            PdfExportJobValidator.chapterResourceKeys(job));
    }

    @Test
    public void rejectsMergedJobFromDifferentAlbums() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("100", "101", 1),
            chapter("200", "102", 2));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));

        assertEquals("合并导出的章节必须属于同一本漫画", error.getMessage());
    }

    @Test
    public void rejectsDuplicateMergedChapter() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("100", "101", 1),
            chapter("100", "101", 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));

        assertEquals("合并导出包含重复章节: 101", error.getMessage());
    }

    @Test
    public void rejectsOutOfOrderPositiveSortValues() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("100", "103", 3),
            chapter("100", "109", 0),
            chapter("100", "102", 2));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));

        assertEquals("合并导出的章节顺序无效", error.getMessage());
    }

    @Test
    public void rejectsMergedJobWithOnlyOneChapter() {
        PdfExportService.ExportJob job = mergedJob(chapter("100", "101", 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));

        assertEquals("合并导出至少需要两个章节", error.getMessage());
    }

    @Test
    public void rejectsUnsafeResourceIds() {
        String[] invalidIds = {"../1", "1/2", "1\\2", "/1", " 1", "1 ", "album-1"};

        for (String invalidId : invalidIds) {
            PdfExportService.ExportJob job = chapterJob("101");
            job.albumId = invalidId;
            assertThrows(IllegalArgumentException.class,
                () -> PdfExportJobValidator.validate(job));
        }
    }

    @Test
    public void rejectsUnsafeMergedChapterId() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("100", "101", 1),
            chapter("100", "../102", 2));

        assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));
    }

    private static PdfExportService.ExportJob chapterJob(String chapterId) {
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "chapter";
        job.albumId = "100";
        job.chapterId = chapterId;
        job.chapterTitle = chapterId;
        job.savePath = "/exports/chapter.pdf";
        return job;
    }

    private static PdfExportService.ExportJob mergedJob(PdfExportService.ExportChapter... chapters) {
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "merged";
        job.albumId = "100";
        job.chapterTitle = "merged";
        job.chapters = Arrays.asList(chapters);
        job.savePath = "/exports/merged.pdf";
        return job;
    }

    private static PdfExportService.ExportChapter chapter(
            String albumId, String chapterId, int sortOrder) {
        PdfExportService.ExportChapter chapter = new PdfExportService.ExportChapter();
        chapter.albumId = albumId;
        chapter.chapterId = chapterId;
        chapter.chapterTitle = chapterId;
        chapter.sortOrder = sortOrder;
        return chapter;
    }
}
