package io.github.jukomu.service;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PdfExportJobValidatorTest {

    @Test
    public void validatesChapterJob() {
        PdfExportService.ExportJob job = chapterJob("chapter-1");

        PdfExportJobValidator.validate(job);

        assertEquals("chapter:album-1:chapter-1", PdfExportJobValidator.taskKey(job));
        assertEquals(Arrays.asList("album-1:chapter-1"),
            PdfExportJobValidator.chapterResourceKeys(job));
    }

    @Test
    public void validatesMergedJobAndBuildsOrderedKeys() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("album-1", "chapter-2", 2),
            chapter("album-1", "extra", 0),
            chapter("album-1", "chapter-3", 3));

        PdfExportJobValidator.validate(job);

        assertEquals("merged:album-1:chapter-2,extra,chapter-3",
            PdfExportJobValidator.taskKey(job));
        assertEquals(Arrays.asList("album-1:chapter-2", "album-1:extra", "album-1:chapter-3"),
            PdfExportJobValidator.chapterResourceKeys(job));
    }

    @Test
    public void rejectsMergedJobFromDifferentAlbums() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("album-1", "chapter-1", 1),
            chapter("album-2", "chapter-2", 2));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));

        assertEquals("合并导出的章节必须属于同一本漫画", error.getMessage());
    }

    @Test
    public void rejectsDuplicateMergedChapter() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("album-1", "chapter-1", 1),
            chapter("album-1", "chapter-1", 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));

        assertEquals("合并导出包含重复章节: chapter-1", error.getMessage());
    }

    @Test
    public void rejectsOutOfOrderPositiveSortValues() {
        PdfExportService.ExportJob job = mergedJob(
            chapter("album-1", "chapter-3", 3),
            chapter("album-1", "extra", 0),
            chapter("album-1", "chapter-2", 2));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));

        assertEquals("合并导出的章节顺序无效", error.getMessage());
    }

    @Test
    public void rejectsMergedJobWithOnlyOneChapter() {
        PdfExportService.ExportJob job = mergedJob(chapter("album-1", "chapter-1", 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PdfExportJobValidator.validate(job));

        assertEquals("合并导出至少需要两个章节", error.getMessage());
    }

    private static PdfExportService.ExportJob chapterJob(String chapterId) {
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "chapter";
        job.albumId = "album-1";
        job.chapterId = chapterId;
        job.chapterTitle = chapterId;
        job.savePath = "/exports/chapter.pdf";
        return job;
    }

    private static PdfExportService.ExportJob mergedJob(PdfExportService.ExportChapter... chapters) {
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "merged";
        job.albumId = "album-1";
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
