package io.github.jukomu.desktop;

import io.github.jukomu.desktop.data.DesktopDatabase;
import io.github.jukomu.desktop.data.DesktopPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopPathsDatabaseTest {
    @Test
    void separatesProgramDirectoryFromLinuxUserDataAndUsesXdgLocations() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-paths-");
        Path program = root.resolve("program");
        Path home = root.resolve("home");
        DesktopPaths paths = new DesktopPaths(
                program,
                home,
                Map.of(
                        "XDG_DATA_HOME", root.resolve("xdg-data").toString(),
                        "XDG_CACHE_HOME", root.resolve("xdg-cache").toString(),
                        "XDG_STATE_HOME", root.resolve("xdg-state").toString()
                ),
                "Linux"
        );

        assertEquals(program.toAbsolutePath().normalize(), paths.programDirectory());
        assertEquals(root.resolve("xdg-data/JQViewer").toAbsolutePath().normalize(), paths.dataDirectory());
        assertEquals(root.resolve("xdg-cache/JQViewer").toAbsolutePath().normalize(), paths.cacheDirectory());
        assertEquals(root.resolve("xdg-state/JQViewer").toAbsolutePath().normalize(), paths.stateDirectory());
        assertNotEquals(paths.programDirectory(), paths.dataDirectory());

        paths.ensureDirectories();
        assertTrue(Files.isDirectory(paths.dataDirectory()));
        assertTrue(Files.isDirectory(paths.logsDirectory()));
        assertTrue(Files.isDirectory(paths.downloadsDirectory()));
        assertTrue(Files.isDirectory(paths.pdfDirectory()));
    }

    @Test
    void usesWindowsLocalAppDataInsteadOfProgramDirectory() {
        Path root = Path.of("build/test-windows").toAbsolutePath();
        DesktopPaths paths = new DesktopPaths(
                root.resolve("program"),
                root.resolve("home"),
                Map.of("LOCALAPPDATA", root.resolve("local-app-data").toString()),
                "Windows 11"
        );

        assertEquals(
                root.resolve("local-app-data/JQViewer").normalize(),
                paths.dataDirectory()
        );
        assertFalse(paths.dataDirectory().startsWith(paths.programDirectory()));
    }

    @Test
    void opensAndMigratesDesktopDatabase() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-db-").resolve("data/desktop.sqlite3");
        try (DesktopDatabase database = new DesktopDatabase(databasePath)) {
            database.open();

            try (ResultSet result = database.connection()
                    .createStatement()
                    .executeQuery("SELECT version FROM desktop_schema_version")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertTrue(database.isOpen());
        }

        assertTrue(Files.isRegularFile(databasePath));
    }
}
