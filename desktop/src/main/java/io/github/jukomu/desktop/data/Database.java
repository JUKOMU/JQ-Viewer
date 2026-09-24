package io.github.jukomu.desktop.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 管理本地 SQLite 连接，并提供版本化 schema 迁移入口。 */
public final class Database implements AutoCloseable {
    private static final int SCHEMA_VERSION = 10;
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path databasePath;
    private final List<Connection> isolatedConnections = new ArrayList<>();
    private Connection connection;

    public Database(Paths paths) {
        this(paths.databasePath());
    }

    public Database(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
    }

    public synchronized Connection open() throws IOException, SQLException {
        if (connection != null && !connection.isClosed()) {
            return connection;
        }

        Path parent = databasePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try {
            Class.forName("org.sqlite.JDBC");
            connection = createConnection();
            migrate(connection);
            return connection;
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC driver is not available", exception);
        } catch (SQLException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /** 为需要独立事务状态的 Store 创建由 Database 统一关闭的连接。 */
    public synchronized Connection openIsolatedConnection() {
        connection();
        try {
            Connection isolated = createConnection();
            isolatedConnections.add(isolated);
            return isolated;
        } catch (SQLException exception) {
            throw new IllegalStateException("无法打开独立数据库连接", exception);
        }
    }

    /** 初始化 schema 版本记录，并创建运行时所需的持久化表。 */
    public static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS desktop_schema_version "
                    + "(version INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS settings "
                    + "(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS browse_history "
                    + "(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " album_id TEXT NOT NULL,"
                    + " album_title TEXT NOT NULL,"
                    + " cover_url TEXT NOT NULL DEFAULT '',"
                    + " authors TEXT NOT NULL DEFAULT '',"
                    + " chapter_id TEXT NOT NULL DEFAULT '',"
                    + " chapter_title TEXT NOT NULL DEFAULT '',"
                    + " file_id INTEGER,"
                    + " timestamp INTEGER NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_browse_history_timestamp_id "
                    + "ON browse_history(timestamp DESC, id DESC)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS parse_history "
                    + "(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " text TEXT NOT NULL,"
                    + " timestamp INTEGER NOT NULL,"
                    + " mode TEXT NOT NULL DEFAULT 'single-mode')");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_parse_history_timestamp_id "
                    + "ON parse_history(timestamp DESC, id DESC)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS offline_folders ("
                    + "folder_id TEXT PRIMARY KEY,"
                    + " name TEXT NOT NULL,"
                    + " created_at INTEGER NOT NULL)"
            );
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS offline_favorites ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " folder_id TEXT NOT NULL,"
                    + " album_id TEXT NOT NULL,"
                    + " title TEXT NOT NULL DEFAULT '',"
                    + " cover_url TEXT NOT NULL DEFAULT '',"
                    + " authors_json TEXT NOT NULL DEFAULT '[]',"
                    + " tags_json TEXT NOT NULL DEFAULT '[]',"
                    + " UNIQUE(folder_id, album_id),"
                    + " FOREIGN KEY(folder_id) REFERENCES offline_folders(folder_id) "
                    + "ON DELETE CASCADE)"
            );
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_offline_favorites_folder_id "
                    + "ON offline_favorites(folder_id, id ASC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_offline_favorites_album_id "
                    + "ON offline_favorites(album_id, id ASC)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS offline_backups ("
                    + "backup_key TEXT PRIMARY KEY,"
                    + " items_json TEXT NOT NULL,"
                    + " created_at INTEGER NOT NULL)"
            );
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS download_tasks ("
                    + "task_id TEXT PRIMARY KEY,"
                    + " album_id TEXT NOT NULL,"
                    + " chapter_id TEXT NOT NULL,"
                    + " album_title TEXT NOT NULL,"
                    + " chapter_title TEXT NOT NULL,"
                    + " cover_url TEXT NOT NULL DEFAULT '',"
                    + " author TEXT NOT NULL DEFAULT '',"
                    + " tags_json TEXT NOT NULL DEFAULT '[]',"
                    + " total_pages INTEGER NOT NULL DEFAULT 0,"
                    + " downloaded_pages INTEGER NOT NULL DEFAULT 0,"
                    + " downloaded_bytes INTEGER NOT NULL DEFAULT 0,"
                    + " first_image_sort_order INTEGER,"
                    + " status TEXT NOT NULL,"
                    + " error TEXT,"
                    + " total_size INTEGER NOT NULL DEFAULT 0,"
                    + " chapter_sort_order INTEGER NOT NULL DEFAULT 0,"
                    + " is_single_episode INTEGER,"
                    + " relative_directory TEXT NOT NULL,"
                    + " created_at INTEGER NOT NULL,"
                    + " failed_at INTEGER,"
                    + " completed_at INTEGER)"
            );
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_download_tasks_created "
                    + "ON download_tasks(created_at DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_download_tasks_chapter "
                    + "ON download_tasks(album_id, chapter_id)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS download_pages ("
                    + "task_id TEXT NOT NULL,"
                    + " sort_order INTEGER NOT NULL,"
                    + " photo_id TEXT NOT NULL,"
                    + " filename TEXT NOT NULL,"
                    + " relative_path TEXT NOT NULL,"
                    + " source_url TEXT NOT NULL DEFAULT '',"
                    + " scramble_id TEXT NOT NULL DEFAULT '',"
                    + " query_params TEXT NOT NULL DEFAULT '',"
                    + " completed INTEGER NOT NULL DEFAULT 0,"
                    + " PRIMARY KEY(task_id, sort_order),"
                    + " FOREIGN KEY(task_id) REFERENCES download_tasks(task_id))"
            );
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_download_pages_photo "
                    + "ON download_pages(photo_id, sort_order)");
        }

        ensureDownloadFailureTimestamp(connection);
        Integer currentVersion = schemaVersion(connection);
        if (currentVersion == null) {
            transaction(connection, () -> {
                createLocalFileSchema(connection);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO desktop_schema_version(version) VALUES (?)")) {
                    statement.setInt(1, SCHEMA_VERSION);
                    statement.executeUpdate();
                }
            });
            return;
        }
        if (currentVersion == 8) {
            transaction(connection, () -> {
                migrateV8ToV9(connection);
                migrateV9ToV10(connection);
            });
            return;
        }
        if (currentVersion == 9) {
            transaction(connection, () -> migrateV9ToV10(connection));
            return;
        }
        if (currentVersion != SCHEMA_VERSION) {
            throw new SQLException("Unsupported desktop schema version: " + currentVersion);
        }
        createLocalFileSchema(connection);
    }

    private static Integer schemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM desktop_schema_version LIMIT 1")) {
            return rows.next() ? rows.getInt(1) : null;
        }
    }

    private static void createLocalFileSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_files ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "format TEXT NOT NULL CHECK(format IN ('pdf','cbz','zip')),"
                    + "file_ref TEXT NOT NULL UNIQUE,"
                    + "display_path TEXT NOT NULL DEFAULT '',"
                    + "file_name TEXT NOT NULL,"
                    + "source_type TEXT NOT NULL CHECK(source_type IN ('imported','exported')),"
                    + "ownership TEXT NOT NULL CHECK(ownership IN ('external_reference','app_created')),"
                    + "chapter_link_status TEXT NOT NULL "
                    + "CHECK(chapter_link_status IN ('resolved','unresolved','multi_chapter')),"
                    + "album_id TEXT NOT NULL,"
                    + "album_title TEXT NOT NULL DEFAULT '',"
                    + "cover_url TEXT NOT NULL DEFAULT '',"
                    + "authors TEXT NOT NULL DEFAULT '',"
                    + "is_single_episode INTEGER NOT NULL DEFAULT -1 "
                    + "CHECK(is_single_episode IN (-1,0,1)),"
                    + "folder_id TEXT,"
                    + "file_size INTEGER NOT NULL DEFAULT 0,"
                    + "page_count INTEGER NOT NULL DEFAULT 0,"
                    + "availability TEXT NOT NULL DEFAULT 'unknown' "
                    + "CHECK(availability IN ('unknown','available','missing','inaccessible','invalid')),"
                    + "verification_status TEXT NOT NULL DEFAULT 'unverified' "
                    + "CHECK(verification_status IN ('unverified','valid','corrupt','page_mismatch')),"
                    + "verification_error TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL,"
                    + "verified_at INTEGER,"
                    + "CHECK(format <> 'zip' OR source_type='exported'))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_local_files_format_source_created "
                    + "ON local_files(format, source_type, created_at DESC, id DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_local_files_availability_updated "
                    + "ON local_files(availability, updated_at DESC, id DESC)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_file_chapters ("
                    + "file_id INTEGER NOT NULL,"
                    + "sequence INTEGER NOT NULL,"
                    + "album_id TEXT NOT NULL,"
                    + "chapter_id TEXT NOT NULL,"
                    + "chapter_title TEXT NOT NULL DEFAULT '',"
                    + "sort_order INTEGER NOT NULL DEFAULT 0,"
                    + "start_page INTEGER NOT NULL DEFAULT 1,"
                    + "end_page INTEGER NOT NULL DEFAULT 0,"
                    + "page_count INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(file_id, sequence),"
                    + "FOREIGN KEY(file_id) REFERENCES local_files(id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_local_file_chapters_album "
                    + "ON local_file_chapters(album_id, sort_order, file_id, sequence)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS export_tasks ("
                    + "export_id TEXT PRIMARY KEY,"
                    + "batch_id TEXT NOT NULL,"
                    + "format TEXT NOT NULL CHECK(format IN ('pdf','cbz','zip')),"
                    + "mode TEXT NOT NULL CHECK(mode IN ('chapter','merged')),"
                    + "album_id TEXT NOT NULL,"
                    + "album_title TEXT NOT NULL DEFAULT '',"
                    + "cover_url TEXT NOT NULL DEFAULT '',"
                    + "authors TEXT NOT NULL DEFAULT '',"
                    + "is_single_episode INTEGER NOT NULL DEFAULT -1 CHECK(is_single_episode IN (-1,0,1)),"
                    + "chapter_id TEXT,"
                    + "display_title TEXT NOT NULL,"
                    + "target_folder_ref TEXT NOT NULL,"
                    + "target_name TEXT NOT NULL,"
                    + "display_path TEXT NOT NULL DEFAULT '',"
                    + "allow_overwrite INTEGER NOT NULL DEFAULT 0 CHECK(allow_overwrite IN (0,1)),"
                    + "use_original INTEGER NOT NULL CHECK(use_original IN (0,1)),"
                    + "compression_ratio REAL NOT NULL,"
                    + "split_pages INTEGER NOT NULL DEFAULT 0,"
                    + "status TEXT NOT NULL,"
                    + "phase TEXT NOT NULL,"
                    + "current_page INTEGER NOT NULL DEFAULT 0,"
                    + "total_pages INTEGER NOT NULL DEFAULT 0,"
                    + "current_volume INTEGER NOT NULL DEFAULT 0,"
                    + "total_volumes INTEGER NOT NULL DEFAULT 0,"
                    + "snapshot_revision INTEGER NOT NULL DEFAULT 0,"
                    + "cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN (0,1)),"
                    + "error_code TEXT,"
                    + "error_message TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "started_at INTEGER,"
                    + "updated_at INTEGER NOT NULL,"
                    + "completed_at INTEGER)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_export_tasks_format_status_updated "
                    + "ON export_tasks(format, status, updated_at DESC, export_id)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS export_task_chapters ("
                    + "export_id TEXT NOT NULL,"
                    + "sequence INTEGER NOT NULL,"
                    + "album_id TEXT NOT NULL,"
                    + "chapter_id TEXT NOT NULL,"
                    + "chapter_title TEXT NOT NULL DEFAULT '',"
                    + "sort_order INTEGER NOT NULL DEFAULT 0,"
                    + "expected_page_count INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(export_id, sequence),"
                    + "FOREIGN KEY(export_id) REFERENCES export_tasks(export_id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS export_task_volumes ("
                    + "export_id TEXT NOT NULL,"
                    + "volume_index INTEGER NOT NULL,"
                    + "start_page INTEGER NOT NULL,"
                    + "end_page INTEGER NOT NULL,"
                    + "expected_page_count INTEGER NOT NULL,"
                    + "actual_page_count INTEGER NOT NULL DEFAULT 0,"
                    + "target_name TEXT NOT NULL,"
                    + "output_file_ref TEXT,"
                    + "display_path TEXT NOT NULL DEFAULT '',"
                    + "temp_path TEXT NOT NULL,"
                    + "status TEXT NOT NULL DEFAULT 'pending',"
                    + "file_size INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at INTEGER NOT NULL,"
                    + "completed_at INTEGER,"
                    + "PRIMARY KEY(export_id, volume_index),"
                    + "FOREIGN KEY(export_id) REFERENCES export_tasks(export_id) ON DELETE CASCADE)");
        }
    }

    private static void migrateV8ToV9(Connection connection) throws SQLException {
        createLocalFileSchema(connection);
        int fileCount = count(connection, "pdf_files");
        int taskCount = count(connection, "pdf_export_tasks");
        int chapterCount = count(connection, "pdf_export_chapters");
        int volumeCount = count(connection, "pdf_export_volumes");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO local_files(id,format,file_ref,display_path,file_name,"
                    + "source_type,ownership,chapter_link_status,album_id,album_title,cover_url,authors,"
                    + "is_single_episode,folder_id,file_size,page_count,availability,verification_status,"
                    + "verification_error,created_at,updated_at,verified_at) "
                    + "SELECT id,'pdf',file_ref,display_path,file_name,source_type,ownership,"
                    + "chapter_link_status,album_id,album_title,cover_url,authors,is_single_episode,"
                    + "folder_id,file_size,page_count,availability,verification_status,verification_error,"
                    + "created_at,updated_at,verified_at FROM pdf_files");
            statement.executeUpdate("INSERT INTO local_file_chapters(file_id,sequence,album_id,chapter_id,"
                    + "chapter_title,sort_order,start_page,end_page,page_count) "
                    + "SELECT id,0,album_id,chapter_id,chapter_title,chapter_sort_order,1,page_count,page_count "
                    + "FROM pdf_files WHERE chapter_id IS NOT NULL AND TRIM(chapter_id)<>''");
            statement.executeUpdate("INSERT INTO export_tasks SELECT export_id,batch_id,'pdf',mode,album_id,"
                    + "album_title,cover_url,authors,is_single_episode,chapter_id,display_title,target_folder_ref,"
                    + "target_name,display_path,allow_overwrite,use_original,compression_ratio,split_pages,status,"
                    + "phase,current_page,total_pages,current_volume,total_volumes,snapshot_revision,cancel_requested,"
                    + "error_code,error_message,created_at,started_at,updated_at,completed_at FROM pdf_export_tasks");
            statement.executeUpdate("INSERT INTO export_task_chapters SELECT * FROM pdf_export_chapters");
            statement.executeUpdate("INSERT INTO export_task_volumes SELECT * FROM pdf_export_volumes");
        }
        rebuildExportedFileChapters(connection);
        verifyCount(connection, "local_files", fileCount);
        verifyCount(connection, "export_tasks", taskCount);
        verifyCount(connection, "export_task_chapters", chapterCount);
        verifyCount(connection, "export_task_volumes", volumeCount);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE pdf_export_chapters");
            statement.executeUpdate("DROP TABLE pdf_export_volumes");
            statement.executeUpdate("DROP TABLE pdf_export_tasks");
            statement.executeUpdate("DROP TABLE pdf_files");
            statement.executeUpdate("UPDATE desktop_schema_version SET version=9");
        }
    }

    private static void migrateV9ToV10(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (!hasColumn(connection, "browse_history", "file_id")) {
                statement.executeUpdate("ALTER TABLE browse_history ADD COLUMN file_id INTEGER");
            }
            statement.executeUpdate("UPDATE desktop_schema_version SET version=10");
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equals(rows.getString("name"))) return true;
            }
            return false;
        }
    }

    private static void rebuildExportedFileChapters(Connection connection) throws SQLException {
        String volumesSql = "SELECT f.id,v.export_id,v.start_page,v.end_page "
                + "FROM local_files f JOIN export_task_volumes v ON v.output_file_ref=f.file_ref "
                + "WHERE f.format='pdf' AND f.source_type='exported'";
        try (Statement statement = connection.createStatement();
             ResultSet volumes = statement.executeQuery(volumesSql)) {
            while (volumes.next()) {
                long fileId = volumes.getLong("id");
                int volumeStart = volumes.getInt("start_page");
                int volumeEnd = volumes.getInt("end_page");
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM local_file_chapters WHERE file_id=?")) {
                    delete.setLong(1, fileId);
                    delete.executeUpdate();
                }
                int chapterStart = 0;
                int sequence = 0;
                try (PreparedStatement chapters = connection.prepareStatement(
                        "SELECT album_id,chapter_id,chapter_title,sort_order,expected_page_count "
                                + "FROM export_task_chapters WHERE export_id=? ORDER BY sequence")) {
                    chapters.setString(1, volumes.getString("export_id"));
                    try (ResultSet rows = chapters.executeQuery()) {
                        while (rows.next()) {
                            int chapterEnd = chapterStart + rows.getInt("expected_page_count");
                            int overlapStart = Math.max(volumeStart, chapterStart);
                            int overlapEnd = Math.min(volumeEnd, chapterEnd);
                            if (overlapStart < overlapEnd) {
                                insertFileChapter(connection, fileId, sequence++, rows,
                                        overlapStart - volumeStart + 1,
                                        overlapEnd - volumeStart,
                                        overlapEnd - overlapStart);
                            }
                            chapterStart = chapterEnd;
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE local_files SET chapter_link_status=? WHERE id=?")) {
                    update.setString(1, sequence > 1 ? "multi_chapter"
                            : sequence == 1 ? "resolved" : "unresolved");
                    update.setLong(2, fileId);
                    update.executeUpdate();
                }
            }
        }
    }

    private static void insertFileChapter(Connection connection, long fileId, int sequence,
                                          ResultSet chapter, int startPage, int endPage,
                                          int pageCount) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO local_file_chapters(file_id,sequence,album_id,chapter_id,chapter_title,"
                        + "sort_order,start_page,end_page,page_count) VALUES (?,?,?,?,?,?,?,?,?)")) {
            insert.setLong(1, fileId);
            insert.setInt(2, sequence);
            insert.setString(3, chapter.getString("album_id"));
            insert.setString(4, chapter.getString("chapter_id"));
            insert.setString(5, chapter.getString("chapter_title"));
            insert.setInt(6, chapter.getInt("sort_order"));
            insert.setInt(7, startPage);
            insert.setInt(8, endPage);
            insert.setInt(9, pageCount);
            insert.executeUpdate();
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void verifyCount(Connection connection, String table, int expected)
            throws SQLException {
        int actual = count(connection, table);
        if (actual != expected) {
            throw new SQLException("Migration row count mismatch for " + table
                    + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void transaction(Connection connection, SqlRunnable operation)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            operation.run();
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }

    private static void ensureDownloadFailureTimestamp(Connection connection) throws SQLException {
        boolean exists = false;
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(download_tasks)")) {
            while (columns.next()) {
                if ("failed_at".equals(columns.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (exists) return;

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE download_tasks ADD COLUMN failed_at INTEGER");
            statement.executeUpdate("UPDATE download_tasks SET failed_at=created_at "
                    + "WHERE status='failed' AND failed_at IS NULL");
        }
    }

    public synchronized Connection connection() {
        if (connection == null) {
            throw new IllegalStateException("数据库尚未打开");
        }
        try {
            if (connection.isClosed()) {
                throw new IllegalStateException("数据库已关闭");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("无法检查数据库状态", exception);
        }
        return connection;
    }

    public synchronized boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException exception) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        for (Connection isolated : isolatedConnections) {
            closeConnection(isolated);
        }
        isolatedConnections.clear();
        if (connection != null) {
            closeConnection(connection);
            connection = null;
        }
    }

    public Path databasePath() {
        return databasePath;
    }

    private Connection createConnection() throws SQLException {
        Connection created = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = created.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
        } catch (SQLException exception) {
            closeConnection(created);
            throw exception;
        }
        return created;
    }

    private static void closeConnection(Connection target) {
        try {
            target.close();
        } catch (SQLException ignored) {
            // SQLite 关闭异常不影响幂等关闭。
        }
    }
}
