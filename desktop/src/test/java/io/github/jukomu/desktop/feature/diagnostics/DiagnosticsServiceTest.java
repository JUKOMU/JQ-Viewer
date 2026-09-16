package io.github.jukomu.desktop.feature.diagnostics;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.image.CacheCapacityPolicy;
import io.github.jukomu.desktop.feature.image.CacheService;
import io.github.jukomu.desktop.feature.image.ImageCache;
import io.github.jukomu.desktop.feature.pdf.export.PdfExportStore;
import io.github.jukomu.desktop.feature.pdf.render.PdfPageCache;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsServiceTest {
    @Test
    void combinesPathsTaskFailuresAndClearableCachesWithoutNewState() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-diagnostics-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        try (Database database = new Database(paths)) {
            database.open();
            DownloadStore downloads = new DownloadStore(database);
            downloads.createOrResetTask(
                    "download-1", "album", "chapter", "漫画", "第一话", "",
                    "album/chapter", 10);
            downloads.fail("download-1", 1, 100, 200, "网络错误");

            PdfExportStore pdfExports = new PdfExportStore(database);
            pdfExports.reserve(new PdfExportStore.ReserveTask(
                    "export-1", "batch", "chapter", "album", "漫画", "", "作者",
                    false, "chapter", "第一话 PDF", "folder:path:/exports", "one.pdf",
                    "/exports/one.pdf", false, true, 1, 0, "failed", "failed", 8,
                    "WRITE_FAILED", "磁盘空间不足", 20
            ), List.of(), List.of());

            ImageCache imageCache = new ImageCache(CacheCapacityPolicy.MIB);
            imageCache.put("chapter/1/image", new byte[]{1, 2, 3}, "image/jpeg");
            PdfPageCache pdfPageCache = new PdfPageCache(paths.cacheDirectory());
            pdfPageCache.write(
                    "a".repeat(64), new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
            CacheService cache = new CacheService(
                    new SettingsService(database), imageCache, pdfPageCache);

            var snapshot = new DiagnosticsService(paths, downloads, pdfExports, cache).snapshot();

            assertEquals(paths.dataDirectory().toString(), snapshot.paths().getFirst().displayPath());
            assertEquals(1, snapshot.tasks().getFirst().failed());
            assertEquals("网络错误",
                    snapshot.tasks().getFirst().recentFailures().getFirst().reason());
            assertEquals(1, snapshot.tasks().get(1).failed());
            assertEquals("磁盘空间不足",
                    snapshot.tasks().get(1).recentFailures().getFirst().reason());
            assertEquals(2, snapshot.clearableResources().size());
            assertEquals(1, snapshot.clearableResources().getFirst().entryCount());
            assertEquals(3, snapshot.clearableResources().getFirst().sizeBytes());
            assertEquals(1, snapshot.clearableResources().get(1).entryCount());
            assertTrue(snapshot.clearableResources().get(1).sizeBytes() > 0);
        }
    }
}
