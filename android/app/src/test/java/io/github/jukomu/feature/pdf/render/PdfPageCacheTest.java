package io.github.jukomu.feature.pdf.render;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PdfPageCacheTest {

    @Test
    public void initializationRemovesOldPngAndTemporaryFiles() throws Exception {
        File directory = Files.createTempDirectory("jq-pdf-pages-init").toFile();
        try {
            File png = new File(directory, "a".repeat(64) + ".png");
            File temporary = new File(directory, "b".repeat(64) + ".123.tmp");
            assertTrue(png.createNewFile());
            assertTrue(temporary.createNewFile());

            new PdfPageCache(directory);

            assertFalse(png.exists());
            assertFalse(temporary.exists());
            assertTrue(directory.isDirectory());
        } finally {
            deleteDirectory(directory);
        }
    }

    @Test
    public void capacityEvictsOldestPngUntilUnder128MiB() throws Exception {
        File directory = Files.createTempDirectory("jq-pdf-pages-capacity").toFile();
        try {
            PdfPageCache cache = new PdfPageCache(directory);
            File oldest = new File(directory, "a".repeat(64) + ".png");
            File newest = new File(directory, "b".repeat(64) + ".png");
            assertTrue(oldest.createNewFile());
            assertTrue(newest.createNewFile());
            oldest.setLength(PdfPageCache.MAX_BYTES / 2L + 1L);
            newest.setLength(PdfPageCache.MAX_BYTES / 2L + 1L);
            oldest.setLastModified(1_000L);
            newest.setLastModified(2_000L);

            cache.enforceCapacity();

            assertFalse(oldest.exists());
            assertTrue(newest.exists());
        } finally {
            deleteDirectory(directory);
        }
    }

    @Test
    public void pagePathIsRestrictedToValidatedResourceId() throws Exception {
        File directory = Files.createTempDirectory("jq-pdf-pages-path").toFile();
        try {
            PdfPageCache cache = new PdfPageCache(directory);

            File page = cache.fileForId("a".repeat(64));
            assertTrue(page.getParentFile().getCanonicalFile().equals(directory.getCanonicalFile()));
            assertThrows(IllegalArgumentException.class,
                () -> cache.fileForId("../outside"));
        } finally {
            deleteDirectory(directory);
        }
    }

    private static void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) file.delete();
        }
        directory.delete();
    }
}
