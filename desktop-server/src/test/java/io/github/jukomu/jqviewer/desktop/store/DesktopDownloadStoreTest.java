package io.github.jukomu.jqviewer.desktop.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopDownloadStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void updateDownloadDirAffectsNewTaskPaths() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path initialDownloads = tempDir.resolve("downloads-a");
        Path nextDownloads = tempDir.resolve("downloads-b");
        DesktopDownloadStore store = new DesktopDownloadStore(dataDir, initialDownloads);

        store.updateDownloadDir(nextDownloads);

        assertEquals(nextDownloads.toAbsolutePath().normalize(), store.downloadDir());
        assertEquals(
            nextDownloads.resolve("album").resolve("chapter").toAbsolutePath().normalize(),
            store.resolveTaskDir("album", "chapter")
        );
        assertTrue(store.isPathInsideDownloadDir(nextDownloads.resolve("album/chapter/1.jpg")));
    }
}
