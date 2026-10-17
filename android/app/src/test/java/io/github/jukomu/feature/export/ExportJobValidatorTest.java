package io.github.jukomu.feature.export;

import io.github.jukomu.feature.localfile.data.LocalFileRef;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ExportJobValidatorTest {

    @Test
    public void validatesChapterJob() {
        ExportService.ExportJob job = chapterJob("101");

        ExportJobValidator.validate(job);

        assertEquals("chapter:100:101", ExportJobValidator.taskKey(job));
        assertEquals(Arrays.asList("100:101"),
            ExportJobValidator.chapterResourceKeys(job));
    }

    @Test
    public void validatesMergedJobAndBuildsOrderedKeys() {
        ExportService.ExportJob job = mergedJob(
            chapter("100", "102", 2),
            chapter("100", "109", 0),
            chapter("100", "103", 3));

        ExportJobValidator.validate(job);

        assertEquals("merged:100:102,109,103",
            ExportJobValidator.taskKey(job));
        assertEquals(Arrays.asList("100:102", "100:109", "100:103"),
            ExportJobValidator.chapterResourceKeys(job));
    }

    @Test
    public void rejectsMergedJobFromDifferentAlbums() {
        ExportService.ExportJob job = mergedJob(
            chapter("100", "101", 1),
            chapter("200", "102", 2));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExportJobValidator.validate(job));

        assertEquals("合并导出的章节必须属于同一本漫画", error.getMessage());
    }

    @Test
    public void rejectsDuplicateMergedChapter() {
        ExportService.ExportJob job = mergedJob(
            chapter("100", "101", 1),
            chapter("100", "101", 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExportJobValidator.validate(job));

        assertEquals("合并导出包含重复章节: 101", error.getMessage());
    }

    @Test
    public void rejectsOutOfOrderPositiveSortValues() {
        ExportService.ExportJob job = mergedJob(
            chapter("100", "103", 3),
            chapter("100", "109", 0),
            chapter("100", "102", 2));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExportJobValidator.validate(job));

        assertEquals("合并导出的章节顺序无效", error.getMessage());
    }

    @Test
    public void rejectsMergedJobWithOnlyOneChapter() {
        ExportService.ExportJob job = mergedJob(chapter("100", "101", 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExportJobValidator.validate(job));

        assertEquals("合并导出至少需要两个章节", error.getMessage());
    }

    @Test
    public void rejectsUnsafeResourceIds() {
        String[] invalidIds = {"../1", "1/2", "1\\2", "/1", " 1", "1 ", "album-1"};

        for (String invalidId : invalidIds) {
            ExportService.ExportJob job = chapterJob("101");
            job.albumId = invalidId;
            assertThrows(IllegalArgumentException.class,
                () -> ExportJobValidator.validate(job));
        }
    }

    @Test
    public void rejectsUnsafeMergedChapterId() {
        ExportService.ExportJob job = mergedJob(
            chapter("100", "101", 1),
            chapter("100", "../102", 2));

        assertThrows(IllegalArgumentException.class,
            () -> ExportJobValidator.validate(job));
    }

    @Test
    public void rejectsFileReferenceAsExportFolder() throws Exception {
        ExportService.ExportJob job = chapterJob("101");
        job.targetFolderRef = LocalFileRef.createPathFileRef("/exports/book.pdf");

        assertThrows(IllegalArgumentException.class,
            () -> ExportJobValidator.validate(job));
    }

    @Test
    public void rejectsInvalidFolderReference() {
        ExportService.ExportJob job = chapterJob("101");
        job.targetFolderRef = "folder:path:relative/exports";

        assertThrows(IllegalArgumentException.class,
            () -> ExportJobValidator.validate(job));
    }

    @Test
    public void rejectsUnsafeTargetNameSegments() {
        String[] invalidNames = {
            "/absolute.pdf",
            "../book.pdf",
            "a/../book.pdf",
            "a/./book.pdf",
            "a//book.pdf",
            "a/",
            "",
            "C:/book.pdf",
        };

        for (String invalidName : invalidNames) {
            ExportService.ExportJob job = chapterJob("101");
            job.targetName = invalidName;
            assertThrows(IllegalArgumentException.class,
                () -> ExportJobValidator.validate(job));
        }
    }

    @Test
    public void preservesLegalNestedTargetName() {
        ExportService.ExportJob job = chapterJob("101");
        job.targetName = "295852/book.pdf";

        ExportJobValidator.validate(job);

        assertEquals("295852/book.pdf", job.targetName);
    }

    @Test
    public void normalizesArchiveJobsAndKeepsOriginalImages() {
        ExportService.ExportJob cbz = chapterJob("101");
        cbz.format = " CBZ ";
        cbz.targetName = "chapter.cbz";
        cbz.useOriginal = false;
        cbz.compressionRatio = 0.25F;

        ExportJobValidator.validate(cbz);

        assertEquals("cbz", cbz.format);
        assertTrue(cbz.useOriginal);
        assertEquals(1F, cbz.compressionRatio, 0F);
    }

    @Test
    public void rejectsTargetExtensionThatDoesNotMatchFormat() {
        ExportService.ExportJob zip = chapterJob("101");
        zip.format = "zip";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExportJobValidator.validate(zip));

        assertEquals("targetName必须以.zip结尾", error.getMessage());
    }

    private static ExportService.ExportJob chapterJob(String chapterId) {
        ExportService.ExportJob job = new ExportService.ExportJob();
        job.mode = "chapter";
        job.albumId = "100";
        job.chapterId = chapterId;
        job.chapterTitle = chapterId;
        job.targetFolderRef = "folder:path:/exports";
        job.targetName = "chapter.pdf";
        job.displayPath = "/exports/chapter.pdf";
        return job;
    }

    private static ExportService.ExportJob mergedJob(ExportService.ExportChapter... chapters) {
        ExportService.ExportJob job = new ExportService.ExportJob();
        job.mode = "merged";
        job.albumId = "100";
        job.chapterTitle = "merged";
        job.chapters = Arrays.asList(chapters);
        job.targetFolderRef = "folder:path:/exports";
        job.targetName = "merged.pdf";
        job.displayPath = "/exports/merged.pdf";
        return job;
    }

    private static ExportService.ExportChapter chapter(
        String albumId, String chapterId, int sortOrder) {
        ExportService.ExportChapter chapter = new ExportService.ExportChapter();
        chapter.albumId = albumId;
        chapter.chapterId = chapterId;
        chapter.chapterTitle = chapterId;
        chapter.sortOrder = sortOrder;
        return chapter;
    }
}
