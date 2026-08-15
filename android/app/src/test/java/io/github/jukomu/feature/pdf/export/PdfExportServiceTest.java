package io.github.jukomu.feature.pdf.export;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class PdfExportServiceTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void buildsSingleVolumeWhenSplitIsDisabled() throws Exception {
        File output = new File(temporaryFolder.getRoot(), "merged.pdf");

        List<PdfExportService.ExportVolume> volumes =
            PdfExportService.buildVolumes(output, 205, 0);

        assertEquals(1, volumes.size());
        assertEquals(0, volumes.get(0).start);
        assertEquals(205, volumes.get(0).end);
        assertSame(output, volumes.get(0).file);
    }

    @Test
    public void buildsVolumesFromTheWholeMergedPageRange() throws Exception {
        File output = new File(temporaryFolder.getRoot(), "merged.pdf");

        List<PdfExportService.ExportVolume> volumes =
            PdfExportService.buildVolumes(output, 205, 100);

        assertEquals(3, volumes.size());
        assertVolume(volumes.get(0), 0, 100, "merged_001-100.pdf");
        assertVolume(volumes.get(1), 100, 200, "merged_101-200.pdf");
        assertVolume(volumes.get(2), 200, 205, "merged_201-205.pdf");
    }

    @Test
    public void removesOnlyTheTargetStaleArtifacts() throws Exception {
        File output = new File(temporaryFolder.getRoot(), "merged.pdf");
        File other = new File(temporaryFolder.getRoot(), "other.pdf.tmp");
        File temp = PdfBoxExportWriter.getTempFile(output);
        File workDir = PdfBoxExportWriter.getWorkDirectory(output);
        File chunk = new File(workDir, "chunk-00000.pdf");

        assertTrue(other.createNewFile());
        assertTrue(temp.createNewFile());
        assertTrue(workDir.mkdirs());
        assertTrue(chunk.createNewFile());

        PdfBoxExportWriter.cleanStaleArtifacts(output);

        assertFalse(temp.exists());
        assertFalse(workDir.exists());
        assertTrue(other.exists());
    }

    @Test
    public void estimatesOriginalExportSpaceFromWorstVolumeAndExistingFinalDelta()
        throws Exception {
        File output = new File(temporaryFolder.getRoot(), "merged.pdf");
        List<PdfExportService.ExportVolume> volumes =
            PdfExportService.buildVolumes(output, 4, 2);
        Files.write(volumes.get(0).file.toPath(), new byte[1_000]);
        List<PdfBoxExportWriter.ExportImageDescriptor> images = Arrays.asList(
            descriptor(100),
            descriptor(100),
            descriptor(100),
            descriptor(100)
        );

        long requiredBytes = PdfExportService.estimateRequiredBytesForExport(
            volumes,
            images,
            true
        );

        assertEquals((16L * 1024L * 1024L) + 440L, requiredBytes);
    }

    @Test
    public void estimatesCompressedVolumeSpaceWithLegacyInputByteBaseline() {
        List<PdfBoxExportWriter.ExportImageDescriptor> images = Arrays.asList(
            descriptor(120),
            descriptor(80)
        );

        long requiredBytes = PdfExportService.estimateRequiredBytesForVolume(images, false);

        assertEquals((16L * 1024L * 1024L) + 400L, requiredBytes);
    }

    @Test
    public void rejectsExistingOutputWithoutOverwritePermission() throws Exception {
        File output = temporaryFolder.newFile("existing.pdf");
        List<PdfExportService.ExportVolume> volumes =
            PdfExportService.buildVolumes(output, 10, 0);

        IOException error = assertThrows(IOException.class,
            () -> PdfExportService.ensureOverwriteAllowed(volumes, false));

        assertTrue(error.getMessage().startsWith("PDF_OUTPUT_EXISTS:"));
        PdfExportService.ensureOverwriteAllowed(volumes, true);
    }

    @Test
    public void retryRequiresThePersistedChapterAndVolumeLayout() throws Exception {
        File output = new File(temporaryFolder.getRoot(), "retry.pdf");
        List<PdfExportService.ExportVolume> volumes =
            PdfExportService.buildVolumes(output, 205, 100);
        PdfExportService.ensureRetryLayoutUnchanged(
            Arrays.asList(100, 105), volumes, Arrays.asList(100, 105), volumes);

        IOException pageError = assertThrows(IOException.class,
            () -> PdfExportService.ensureRetryLayoutUnchanged(
                Arrays.asList(100, 104), volumes, Arrays.asList(100, 105), volumes));
        assertTrue(pageError.getMessage().startsWith("PDF_RETRY_LAYOUT_CHANGED:"));
    }

    @Test
    public void retryRejectsChangedVolumePath() throws Exception {
        File output = new File(temporaryFolder.getRoot(), "retry.pdf");
        List<PdfExportService.ExportVolume> volumes =
            PdfExportService.buildVolumes(output, 205, 100);
        List<PdfExportService.ExportVolume> persistedVolumes = Arrays.asList(
            volumes.get(0),
            new PdfExportService.ExportVolume(
                volumes.get(1).start,
                volumes.get(1).end,
                new File(temporaryFolder.getRoot(), "other.pdf")),
            volumes.get(2));

        IOException error = assertThrows(IOException.class,
            () -> PdfExportService.ensureRetryLayoutUnchanged(
                Arrays.asList(205), persistedVolumes, Arrays.asList(205), volumes));

        assertTrue(error.getMessage().startsWith("PDF_RETRY_LAYOUT_CHANGED:"));
    }

    private static void assertVolume(PdfExportService.ExportVolume volume, int start, int end,
                                     String fileName) {
        assertEquals(start, volume.start);
        assertEquals(end, volume.end);
        assertEquals(fileName, volume.file.getName());
    }

    private static PdfBoxExportWriter.ExportImageDescriptor descriptor(long fileBytes) {
        return new PdfBoxExportWriter.ExportImageDescriptor(
            new File("image.jpg"),
            "image/jpeg",
            10,
            10,
            fileBytes,
            true
        );
    }
}
