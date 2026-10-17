package io.github.jukomu.desktop.feature.pdf.render;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PdfPageCacheTest {
    @Test
    void writeSucceedsWhenCapacityEvictsTheWrittenTarget() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-pdf-page-cache-");
        try {
            PdfPageCache cache = new PdfPageCache(root);
            Path olderTarget = cache.fileFor("a".repeat(64));
            Path newerLargePage = cache.fileFor("b".repeat(64));
            Files.createDirectories(olderTarget.getParent());
            Files.createFile(newerLargePage);
            try (var file = java.nio.channels.FileChannel.open(newerLargePage,
                    java.nio.file.StandardOpenOption.WRITE)) {
                file.position(PdfPageCache.MAX_BYTES);
                file.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
            }
            Files.setLastModifiedTime(newerLargePage,
                FileTime.fromMillis(System.currentTimeMillis() + 60_000L));

            assertDoesNotThrow(() -> cache.write("a".repeat(64),
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)));
            assertFalse(Files.exists(olderTarget));
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (Files.notExists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                    }
                });
        }
    }
}
