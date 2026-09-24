package io.github.jukomu.desktop;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                assertEquals(10, result.getInt(1));
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
                    .getTables(null, null, "local_files", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "local_file_chapters", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "export_tasks", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "export_task_chapters", null)) {
                assertTrue(result.next());
            }
            try (ResultSet result = database.connection().getMetaData()
                    .getTables(null, null, "export_task_volumes", null)) {
                assertTrue(result.next());
            }
            assertTrue(database.isOpen());
        }

        assertTrue(Files.isRegularFile(databasePath));
    }

    @Test
    void migratesVersionEightPdfDataToGenericLocalFileSchema() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-db-upgrade-")
                .resolve("desktop.sqlite3");
        createVersionEightDatabase(databasePath);

        try (Database database = new Database(databasePath)) {
            database.open();
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT version FROM desktop_schema_version")) {
                assertTrue(result.next());
                assertEquals(10, result.getInt(1));
            }
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT format,chapter_link_status FROM local_files "
                            + "WHERE file_ref='imported-ref'")) {
                assertTrue(result.next());
                assertEquals("pdf", result.getString("format"));
                assertEquals("resolved", result.getString("chapter_link_status"));
            }
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT chapter_id,start_page,end_page,page_count "
                            + "FROM local_file_chapters WHERE file_id=1")) {
                assertTrue(result.next());
                assertEquals("chapter-imported", result.getString("chapter_id"));
                assertEquals(1, result.getInt("start_page"));
                assertEquals(4, result.getInt("end_page"));
                assertEquals(4, result.getInt("page_count"));
            }
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT format FROM export_tasks WHERE export_id='export-1'")) {
                assertTrue(result.next());
                assertEquals("pdf", result.getString("format"));
            }
            try (ResultSet result = database.connection().createStatement()
                    .executeQuery("SELECT chapter_id,start_page,end_page,page_count "
                            + "FROM local_file_chapters WHERE file_id=2 ORDER BY sequence")) {
                assertTrue(result.next());
                assertEquals("chapter-1", result.getString("chapter_id"));
                assertEquals(1, result.getInt("start_page"));
                assertEquals(2, result.getInt("end_page"));
                assertEquals(2, result.getInt("page_count"));
                assertTrue(result.next());
                assertEquals("chapter-2", result.getString("chapter_id"));
                assertEquals(3, result.getInt("start_page"));
                assertEquals(5, result.getInt("end_page"));
                assertEquals(3, result.getInt("page_count"));
                assertFalse(result.next());
            }
            assertFalse(tableExists(database, "pdf_files"));
            assertFalse(tableExists(database, "pdf_export_tasks"));
            assertFalse(tableExists(database, "pdf_export_chapters"));
            assertFalse(tableExists(database, "pdf_export_volumes"));
        }
    }

    @Test
    void rejectsUnsupportedSchemaInsteadOfAddingMigrationFallbacks() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-db-upgrade-downloads-")
                .resolve("desktop.sqlite3");
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE desktop_schema_version "
                    + "(version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO desktop_schema_version(version) VALUES (7)");
        }

        try (Database database = new Database(databasePath)) {
            SQLException error = assertThrows(SQLException.class, database::open);
            assertTrue(error.getMessage().contains("Unsupported desktop schema version: 7"));
        }
    }

    private static boolean tableExists(Database database, String table) throws SQLException {
        try (ResultSet result = database.connection().getMetaData()
                .getTables(null, null, table, null)) {
            return result.next();
        }
    }

    private static void createVersionEightDatabase(Path databasePath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE desktop_schema_version (version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO desktop_schema_version(version) VALUES (8)");
            statement.executeUpdate("CREATE TABLE pdf_files ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,file_ref TEXT NOT NULL UNIQUE,"
                    + "display_path TEXT NOT NULL DEFAULT '',file_name TEXT NOT NULL,"
                    + "source_type TEXT NOT NULL,ownership TEXT NOT NULL,chapter_link_status TEXT NOT NULL,"
                    + "album_id TEXT NOT NULL,album_title TEXT NOT NULL DEFAULT '',cover_url TEXT NOT NULL DEFAULT '',"
                    + "authors TEXT NOT NULL DEFAULT '',chapter_id TEXT,chapter_title TEXT NOT NULL DEFAULT '',"
                    + "chapter_sort_order INTEGER NOT NULL DEFAULT 0,is_single_episode INTEGER NOT NULL DEFAULT -1,"
                    + "folder_id TEXT,file_size INTEGER NOT NULL DEFAULT 0,page_count INTEGER NOT NULL DEFAULT 0,"
                    + "availability TEXT NOT NULL DEFAULT 'unknown',verification_status TEXT NOT NULL DEFAULT 'unverified',"
                    + "verification_error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,verified_at INTEGER)");
            statement.executeUpdate("CREATE TABLE pdf_export_tasks ("
                    + "export_id TEXT PRIMARY KEY,batch_id TEXT NOT NULL,mode TEXT NOT NULL,album_id TEXT NOT NULL,"
                    + "album_title TEXT NOT NULL DEFAULT '',cover_url TEXT NOT NULL DEFAULT '',authors TEXT NOT NULL DEFAULT '',"
                    + "is_single_episode INTEGER NOT NULL DEFAULT -1,chapter_id TEXT,display_title TEXT NOT NULL,"
                    + "target_folder_ref TEXT NOT NULL,target_name TEXT NOT NULL,display_path TEXT NOT NULL DEFAULT '',"
                    + "allow_overwrite INTEGER NOT NULL DEFAULT 0,use_original INTEGER NOT NULL,compression_ratio REAL NOT NULL,"
                    + "split_pages INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,phase TEXT NOT NULL,"
                    + "current_page INTEGER NOT NULL DEFAULT 0,total_pages INTEGER NOT NULL DEFAULT 0,"
                    + "current_volume INTEGER NOT NULL DEFAULT 0,total_volumes INTEGER NOT NULL DEFAULT 0,"
                    + "snapshot_revision INTEGER NOT NULL DEFAULT 0,cancel_requested INTEGER NOT NULL DEFAULT 0,"
                    + "error_code TEXT,error_message TEXT,created_at INTEGER NOT NULL,started_at INTEGER,"
                    + "updated_at INTEGER NOT NULL,completed_at INTEGER)");
            statement.executeUpdate("CREATE TABLE pdf_export_chapters ("
                    + "export_id TEXT NOT NULL,sequence INTEGER NOT NULL,album_id TEXT NOT NULL,chapter_id TEXT NOT NULL,"
                    + "chapter_title TEXT NOT NULL DEFAULT '',sort_order INTEGER NOT NULL DEFAULT 0,"
                    + "expected_page_count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(export_id,sequence))");
            statement.executeUpdate("CREATE TABLE pdf_export_volumes ("
                    + "export_id TEXT NOT NULL,volume_index INTEGER NOT NULL,start_page INTEGER NOT NULL,end_page INTEGER NOT NULL,"
                    + "expected_page_count INTEGER NOT NULL,actual_page_count INTEGER NOT NULL DEFAULT 0,target_name TEXT NOT NULL,"
                    + "output_file_ref TEXT,display_path TEXT NOT NULL DEFAULT '',temp_path TEXT NOT NULL,"
                    + "status TEXT NOT NULL DEFAULT 'pending',file_size INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,"
                    + "completed_at INTEGER,PRIMARY KEY(export_id,volume_index))");
            statement.executeUpdate("INSERT INTO pdf_files VALUES "
                    + "(1,'imported-ref','Imported','imported.pdf','imported','external_reference','resolved',"
                    + "'album-1','Album','','Author','chapter-imported','Imported chapter',1,0,'folder-1',40,4,"
                    + "'available','valid',NULL,10,11,11),"
                    + "(2,'exported-ref','Exported','merged.pdf','exported','app_created','multi_chapter',"
                    + "'album-1','Album','','Author',NULL,'',0,0,NULL,50,5,'available','valid',NULL,20,21,21)");
            statement.executeUpdate("INSERT INTO pdf_export_tasks VALUES ("
                    + "'export-1','batch-1','merged','album-1','Album','','Author',0,NULL,'Merged',"
                    + "'folder-ref','merged.pdf','Exported',0,1,1.0,0,'completed','completed',5,5,1,1,1,0,"
                    + "NULL,NULL,20,20,21,21)");
            statement.executeUpdate("INSERT INTO pdf_export_chapters VALUES "
                    + "('export-1',0,'album-1','chapter-1','Chapter 1',1,2),"
                    + "('export-1',1,'album-1','chapter-2','Chapter 2',2,3)");
            statement.executeUpdate("INSERT INTO pdf_export_volumes VALUES ("
                    + "'export-1',0,0,5,5,5,'merged.pdf','exported-ref','Exported','temp.pdf','completed',50,21,21)");
        }
    }
}
