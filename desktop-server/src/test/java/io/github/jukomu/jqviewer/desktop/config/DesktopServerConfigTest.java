package io.github.jukomu.jqviewer.desktop.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopServerConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsKeepDesktopDataUnderUserDataDirectory() throws Exception {
        Path dataDir = tempDir.resolve("data");

        DesktopServerConfig config = DesktopServerConfig.from(
            new String[]{"--data-dir", dataDir.toString()},
            Map.of()
        );

        assertEquals("127.0.0.1", config.bindAddress().getHostAddress());
        assertEquals(18080, config.port());
        assertFalse(config.token().isBlank());
        assertEquals(dataDir.toAbsolutePath().normalize(), config.dataDir());
        assertEquals(dataDir.resolve("cache").toAbsolutePath().normalize(), config.cacheDir());
        assertEquals(dataDir.resolve("logs").toAbsolutePath().normalize(), config.logDir());
        assertEquals(dataDir.resolve("downloads").toAbsolutePath().normalize(), config.downloadDir());
        assertEquals(dataDir.resolve("pdf-imports").toAbsolutePath().normalize(), config.pdfRootDir());
        assertEquals(dataDir.resolve("pdf-exports").toAbsolutePath().normalize(), config.pdfExportDir());
    }

    @Test
    void commandLineOverridesMutableDirectoriesAndToken() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path downloads = tempDir.resolve("downloads");
        Path pdfRoot = tempDir.resolve("pdf-root");
        Path pdfExports = tempDir.resolve("pdf-exports");
        Path staticDir = tempDir.resolve("dist");

        DesktopServerConfig config = DesktopServerConfig.from(
            new String[]{
                "--port=19090",
                "--token", "cli-token",
                "--data-dir", dataDir.toString(),
                "--download-dir", downloads.toString(),
                "--pdf-root-dir", pdfRoot.toString(),
                "--pdf-export-dir", pdfExports.toString(),
                "--static-dir", staticDir.toString()
            },
            Map.of(
                "JQ_DESKTOP_TOKEN", "env-token",
                "JQ_DESKTOP_PORT", "18081"
            )
        );

        assertEquals(19090, config.port());
        assertEquals("cli-token", config.token());
        assertEquals(dataDir.toAbsolutePath().normalize(), config.dataDir());
        assertEquals(downloads.toAbsolutePath().normalize(), config.downloadDir());
        assertEquals(pdfRoot.toAbsolutePath().normalize(), config.pdfRootDir());
        assertEquals(pdfExports.toAbsolutePath().normalize(), config.pdfExportDir());
        assertEquals(staticDir.toAbsolutePath().normalize(), config.staticDir());
    }

    @Test
    void devOriginsOnlyAllowLocalLoopbackOrigins() throws Exception {
        DesktopServerConfig config = DesktopServerConfig.from(
            new String[]{"--data-dir", tempDir.resolve("data").toString()},
            Map.of(
                "JQ_DESKTOP_DEV_ORIGINS",
                "http://evil.example,http://localhost:5173,https://127.0.0.1:5174"
            )
        );

        assertTrue(config.allowedOrigins().contains("http://localhost:5173"));
        assertTrue(config.allowedOrigins().contains("https://127.0.0.1:5174"));
        assertFalse(config.allowedOrigins().contains("http://evil.example"));
    }

    @Test
    void withPortPreservesSecurityAndDirectoryBoundaries() throws Exception {
        DesktopServerConfig config = DesktopServerConfig.from(
            new String[]{"--data-dir", tempDir.resolve("data").toString(), "--token", "same-token"},
            Map.of()
        );

        DesktopServerConfig changed = config.withPort(0);

        assertEquals(0, changed.port());
        assertEquals(config.bindAddress(), changed.bindAddress());
        assertEquals("same-token", changed.token());
        assertEquals(config.dataDir(), changed.dataDir());
        assertEquals(config.cacheDir(), changed.cacheDir());
        assertEquals(config.logDir(), changed.logDir());
        assertEquals(config.downloadDir(), changed.downloadDir());
        assertEquals(config.pdfRootDir(), changed.pdfRootDir());
        assertEquals(config.pdfExportDir(), changed.pdfExportDir());
    }
}
