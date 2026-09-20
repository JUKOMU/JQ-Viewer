package io.github.jukomu.desktop;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
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
            Database.migrate(database.connection());

            try (ResultSet result = database.connection()
                    .createStatement()
                    .executeQuery("SELECT version FROM desktop_schema_version")) {
                assertTrue(result.next());
                assertEquals(8, result.getInt(1));
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "browse_history", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "parse_history", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "download_tasks", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "offline_folders", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "offline_favorites", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "offline_backups", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "download_pages", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "pdf_files", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "pdf_export_tasks", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "pdf_export_chapters", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "pdf_export_volumes", null)) {
                assertTrue(result.next());
            }
            assertTrue(database.isOpen());
        }

        assertTrue(Files.isRegularFile(databasePath));
    }

    @Test
    void upgradesVersionSixWithoutReplacingExistingDesktopData() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-db-upgrade-")
                .resolve("desktop.sqlite3");
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE desktop_schema_version (version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO desktop_schema_version(version) VALUES (6)");
            statement.executeUpdate("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO settings(key, value) "
                    + "VALUES ('reader_preload_pages', '23')");
            statement.executeUpdate("CREATE TABLE browse_history ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " album_id TEXT NOT NULL,"
                    + " album_title TEXT NOT NULL,"
                    + " cover_url TEXT NOT NULL DEFAULT '',"
                    + " authors TEXT NOT NULL DEFAULT '',"
                    + " chapter_id TEXT NOT NULL DEFAULT '',"
                    + " chapter_title TEXT NOT NULL DEFAULT '',"
                    + " timestamp INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO browse_history("
                    + "album_id, album_title, timestamp) VALUES ('album-1', 'Existing', 100)");
        }

        try (Database database = new Database(databasePath)) {
            database.open();
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT version FROM desktop_schema_version")) {
                assertTrue(result.next());
                assertEquals(8, result.getInt(1));
            }
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT value FROM settings "
                            + "WHERE key = 'reader_preload_pages'")) {
                assertTrue(result.next());
                assertEquals("23", result.getString(1));
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "offline_favorites", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT album_title FROM browse_history WHERE album_id = 'album-1'")) {
                assertTrue(result.next());
                assertEquals("Existing", result.getString(1));
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "parse_history", null)) {
                assertTrue(result.next());
            }
        }
    }

    @Test
    void upgradesVersionSevenDownloadTasksWithoutLosingFailures() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-db-upgrade-downloads-")
                .resolve("desktop.sqlite3");
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE desktop_schema_version "
                    + "(version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO desktop_schema_version(version) VALUES (7)");
            statement.executeUpdate("CREATE TABLE download_tasks ("
                    + "task_id TEXT PRIMARY KEY,"
                    + "album_id TEXT NOT NULL,"
                    + "chapter_id TEXT NOT NULL,"
                    + "status TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO download_tasks("
                    + "task_id,album_id,chapter_id,status,created_at) "
                    + "VALUES ('download-1','album-1','chapter-1','failed',123)");
        }

        try (Database database = new Database(databasePath)) {
            database.open();
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT version FROM desktop_schema_version")) {
                assertTrue(result.next());
                assertEquals(8, result.getInt(1));
            }
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT failed_at FROM download_tasks "
                            + "WHERE task_id='download-1'")) {
                assertTrue(result.next());
                assertEquals(123, result.getLong(1));
            }
        }
    }
}
