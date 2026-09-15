package io.github.jukomu.desktop.feature.image;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.pdf.render.PdfPageCache;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheServiceTest {
    @Test
    void appliesHeapBudgetPersistsRequestedCapacityAndEnumeratesEntries() throws Exception {
        Fixture fixture = fixture();
        Database database = fixture.database();
        try (database) {
            fixture.imageCache().put(
                    "photo-20/49/image", new byte[(int) CacheCapacityPolicy.MIB], "image/jpeg");
            fixture.imageCache().put("photo-20/50/thumb", new byte[]{1, 2, 3}, "image/png");

            var updated = fixture.service().setCapacity(1024);
            var contents = fixture.service().contents();

            assertTrue(updated.success());
            assertEquals(1024, updated.requestedMb());
            assertEquals(83, updated.effectiveMb());
            assertEquals("heap-budget", updated.limitReason());
            assertEquals(1, updated.usedMb());
            assertEquals(1024, new SettingsService(database).cacheCapacityMb());
            assertEquals(2, contents.entries().size());
            assertEquals("photo-20", contents.entries().get(0).photoId());
            assertEquals(49, contents.entries().get(0).sortOrder());
            assertEquals("image", contents.entries().get(0).type());
            assertEquals("thumb", contents.entries().get(1).type());
        }
    }

    @Test
    void clearRemovesOnlyImageAndPdfPageCaches() throws Exception {
        Fixture fixture = fixture();
        Database database = fixture.database();
        try (database) {
            String resourceId = "a".repeat(64);
            fixture.imageCache().put("photo-20/1/image", new byte[]{1, 2, 3}, "image/jpeg");
            fixture.pdfPageCache().write(
                    resourceId, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
            Path download = fixture.paths().downloadsDirectory().resolve("chapter/001.jpg");
            Path pdf = fixture.paths().pdfDirectory().resolve("book.pdf");
            Files.createDirectories(download.getParent());
            Files.write(download, new byte[]{4, 5, 6});
            Files.write(pdf, new byte[]{7, 8, 9});
            fixture.settings().setCacheCapacityMb(512);

            fixture.service().clear();

            assertEquals(0, fixture.imageCache().usedBytes());
            assertFalse(fixture.pdfPageCache().contains(resourceId));
            assertTrue(Files.isRegularFile(download));
            assertTrue(Files.isRegularFile(pdf));
            assertEquals(512, fixture.settings().cacheCapacityMb());
        }
    }

    private static Fixture fixture() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-cache-service-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        Database database = new Database(paths);
        database.open();
        SettingsService settings = new SettingsService(database);
        ImageCache imageCache = new ImageCache(1);
        PdfPageCache pdfPageCache = new PdfPageCache(paths.cacheDirectory());
        CacheService service = new CacheService(
                settings,
                imageCache,
                pdfPageCache,
                new CacheCapacityPolicy(),
                128L * CacheCapacityPolicy.MIB
        );
        return new Fixture(paths, database, settings, imageCache, pdfPageCache, service);
    }

    private record Fixture(
            Paths paths,
            Database database,
            SettingsService settings,
            ImageCache imageCache,
            PdfPageCache pdfPageCache,
            CacheService service
    ) {
    }
}
