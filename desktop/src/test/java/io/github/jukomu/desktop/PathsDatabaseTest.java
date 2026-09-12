package io.github.jukomu.desktop;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathsDatabaseTest {
    @Test
    void separatesProgramDirectoryFromLinuxUserDataAndUsesXdgLocations() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-paths-");
        Path program = root.resolve("program");
        Path home = root.resolve("home");
        Paths paths = new Paths(
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
    void treatsDarwinAsMacOsBeforeCheckingWindows() {
        Path root = Path.of("build/test-darwin").toAbsolutePath();
        Paths paths = new Paths(
                root.resolve("program"),
                root.resolve("home"),
                Map.of(),
                "Darwin"
        );

        assertEquals(
                root.resolve("home/Library/Application Support/JQViewer").normalize(),
                paths.dataDirectory()
        );
        assertEquals(
                root.resolve("home/Library/Caches/JQViewer").normalize(),
                paths.cacheDirectory()
        );
        assertEquals(
                root.resolve("home/Library/Application Support/JQViewer/state").normalize(),
                paths.stateDirectory()
        );
    }

    @Test
    void usesWindowsLocalAppDataInsteadOfProgramDirectory() {
        Path root = Path.of("build/test-windows").toAbsolutePath();
        Paths paths = new Paths(
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
    void opensAndMigratesDatabase() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-db-").resolve("data/desktop.sqlite3");
        try (Database database = new Database(databasePath)) {
            database.open();

            try (ResultSet result = database.connection()
                    .createStatement()
                    .executeQuery("SELECT version FROM desktop_schema_version")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "browse_history", null)) {
                assertTrue(result.next());
            }
            assertTrue(database.isOpen());
        }

        assertTrue(Files.isRegularFile(databasePath));
    }
}
