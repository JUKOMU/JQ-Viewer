package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.jukomu.jqviewer.desktop.store.DesktopDownloadStore;
import io.github.jukomu.jqviewer.desktop.store.DesktopPdfStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopPdfServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void updateRootsSwitchesPdfPathWhitelist() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path downloadDir = tempDir.resolve("downloads");
        Path oldRoot = tempDir.resolve("old-pdf-root");
        Path oldExport = tempDir.resolve("old-pdf-export");
        Path newRoot = tempDir.resolve("new-pdf-root");
        Path newExport = tempDir.resolve("new-pdf-export");
        Files.createDirectories(oldRoot);
        Files.createDirectories(oldExport);
        Files.createDirectories(newRoot);
        Files.createDirectories(newExport);
        Path oldFile = oldRoot.resolve("old.pdf");
        Path newFile = newRoot.resolve("new.pdf");
        Files.writeString(oldFile, "old");
        Files.writeString(newFile, "new");

        DesktopEventBroker eventBroker = new DesktopEventBroker();
        DesktopDownloadService downloadService = new DesktopDownloadService(
            null,
            new DesktopDownloadStore(dataDir, downloadDir),
            eventBroker,
            1
        );
        DesktopPdfService service = new DesktopPdfService(
            new DesktopPdfStore(dataDir),
            downloadService,
            eventBroker,
            oldRoot,
            oldExport
        );

        assertEquals(1, existingCount(service, oldFile));

        JsonObject patch = new JsonObject();
        patch.addProperty("pdfRootDir", newRoot.toString());
        patch.addProperty("pdfExportDir", newExport.toString());
        JsonObject roots = service.updateRoots(patch);

        assertEquals(newRoot.toAbsolutePath().normalize().toString(), roots.get("pdfRootDir").getAsString());
        assertEquals(newExport.toAbsolutePath().normalize().toString(), roots.get("pdfExportDir").getAsString());
        assertEquals(0, existingCount(service, oldFile));
        assertEquals(1, existingCount(service, newFile));
        service.stop();
        downloadService.stop();
        eventBroker.close();
    }

    private static int existingCount(DesktopPdfService service, Path path) {
        JsonObject body = new JsonObject();
        JsonArray paths = new JsonArray();
        paths.add(path.toString());
        body.add("paths", paths);
        return service.checkFiles(body).getAsJsonArray("existing").size();
    }
}
