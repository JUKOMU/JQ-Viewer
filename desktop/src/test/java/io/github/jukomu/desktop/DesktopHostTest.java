package io.github.jukomu.desktop;

import io.github.jukomu.desktop.backend.DesktopBackend;
import io.github.jukomu.desktop.data.DesktopPaths;
import io.github.jukomu.desktop.host.BrowserLauncher;
import io.github.jukomu.desktop.host.DesktopHost;
import io.github.jukomu.desktop.host.SingleInstanceGuard;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopHostTest {
    @Test
    void secondHostDoesNotStartAnotherBackendAndReopensPrimaryHome() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-host-");
        DesktopPaths paths = new DesktopPaths(
                root.resolve("program"),
                root.resolve("home"),
                Map.of(),
                "Linux"
        );
        List<String> opened = new ArrayList<>();
        DesktopHost primary = new DesktopHost(
                new DesktopBackend(paths, new InitOnlyPlugin()),
                new SingleInstanceGuard(paths),
                new BrowserLauncher(uri -> opened.add(uri.toString()))
        );
        DesktopHost secondary = new DesktopHost(
                new DesktopBackend(paths, new InitOnlyPlugin()),
                new SingleInstanceGuard(paths),
                new BrowserLauncher(uri -> opened.add(uri.toString()))
        );

        try {
            assertTrue(primary.start());
            assertFalse(secondary.start());
            assertEquals(primary.homeUrl().toString(), opened.getFirst());
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (opened.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(2, opened.size());
            assertEquals(primary.homeUrl().toString(), opened.get(1));
            assertFalse(secondary.backend().isRunning());
            assertTrue(primary.backend().isRunning());
        } finally {
            secondary.close();
            primary.close();
        }

        assertFalse(primary.isStarted());
        assertFalse(primary.isPrimary());
    }
}
