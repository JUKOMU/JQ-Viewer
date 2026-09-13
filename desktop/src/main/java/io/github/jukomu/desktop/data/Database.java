package io.github.jukomu.desktop.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** 管理本地 SQLite 连接，并提供版本化 schema 迁移入口。 */
public final class Database implements AutoCloseable {
    private static final int SCHEMA_VERSION = 3;

    private final Path databasePath;
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
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            migrate(connection);
            return connection;
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC driver is not available", exception);
        } catch (SQLException | RuntimeException exception) {
            close();
            throw exception;
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
                    + " timestamp INTEGER NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_browse_history_timestamp_id "
                    + "ON browse_history(timestamp DESC, id DESC)");
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

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO desktop_schema_version(version) "
                        + "SELECT 1 WHERE NOT EXISTS "
                        + "(SELECT 1 FROM desktop_schema_version)"
        )) {
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE desktop_schema_version SET version = ? WHERE version < ?"
        )) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.setInt(2, SCHEMA_VERSION);
            statement.executeUpdate();
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
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // SQLite 关闭异常不影响幂等关闭。
        } finally {
            connection = null;
        }
    }

    public Path databasePath() {
        return databasePath;
    }
}
