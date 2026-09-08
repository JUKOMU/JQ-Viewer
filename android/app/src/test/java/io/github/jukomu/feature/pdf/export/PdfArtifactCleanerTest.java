package io.github.jukomu.feature.pdf.export;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PdfArtifactCleanerTest {

    @Test
    public void stagingCleanupCountsBeforeDeletingOnlyTheControlledChild() throws Exception {
        File cacheRoot = Files.createTempDirectory("jq-pdf-export-cache").toFile();
        try {
            File staging = new File(cacheRoot, "export-1");
            assertTrue(staging.mkdirs());
            File output = new File(staging, "book.pdf");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write(new byte[]{1, 2, 3});
            }
            File sibling = new File(cacheRoot, "user-output.pdf");
            try (FileOutputStream stream = new FileOutputStream(sibling)) {
                stream.write(new byte[]{4});
            }

            assertEquals(3L, PdfArtifactCleaner.cleanupStagingDirectory(cacheRoot, "export-1"));
            assertFalse(staging.exists());
            assertTrue(sibling.exists());
        } finally {
            deleteRecursively(cacheRoot);
        }
    }

    @Test
    public void stagingCleanupRejectsTraversal() throws Exception {
        File cacheRoot = Files.createTempDirectory("jq-pdf-export-cache-safe").toFile();
        try {
            assertThrows(Exception.class,
                () -> PdfArtifactCleaner.cleanupStagingDirectory(cacheRoot, "../outside"));
        } finally {
            deleteRecursively(cacheRoot);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
