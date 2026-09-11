package io.github.jukomu.desktop.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** 管理 Desktop SQLite 连接，并提供 schema migration 入口。 */
public final class DesktopDatabase implements AutoCloseable {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private final Path databasePath;
    private Connection connection;

    public DesktopDatabase(DesktopPaths paths) {
        this(paths.databasePath());
    }

    public DesktopDatabase(Path databasePath) {
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

    /** 初始化 schema version ledger，供版本化迁移使用。 */
    public static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS desktop_schema_version "
                            + "(version INTEGER NOT NULL)"
            );
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO desktop_schema_version(version) "
                        + "SELECT 1 WHERE NOT EXISTS "
                        + "(SELECT 1 FROM desktop_schema_version)"
        )) {
            statement.executeUpdate();
        }

        int version;
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT version FROM desktop_schema_version LIMIT 1")) {
            if (!result.next()) {
                throw new SQLException("Desktop schema version ledger is empty");
            }
            version = result.getInt(1);
        }

        if (version >= CURRENT_SCHEMA_VERSION) {
            return;
        }

        boolean autoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            createBusinessSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE desktop_schema_version SET version = ?")) {
                statement.setInt(1, CURRENT_SCHEMA_VERSION);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void createBusinessSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS desktop_settings (
                        key TEXT PRIMARY KEY NOT NULL,
                        value TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS browse_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        album_id TEXT NOT NULL,
                        album_title TEXT NOT NULL,
                        cover_url TEXT NOT NULL DEFAULT '',
                        authors TEXT NOT NULL DEFAULT '',
                        chapter_id TEXT NOT NULL DEFAULT '',
                        chapter_title TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_browse_history_timestamp_id "
                            + "ON browse_history (timestamp DESC, id DESC)"
            );
        }

        insertDefaultSettings(connection);
    }

    private static void insertDefaultSettings(Connection connection) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO desktop_settings(key, value, updated_at) VALUES (?, ?, ?)")) {
            insertDefaultSetting(statement, "reader_preload_pages", "15", now);
            insertDefaultSetting(statement, "preload_concurrency", "6", now);
            insertDefaultSetting(statement, "download_concurrency", "6", now);
            insertDefaultSetting(statement, "download_public", "false", now);
            insertDefaultSetting(statement, "cache_capacity_mb", "256", now);
            insertDefaultSetting(statement, "ocr_enabled", "true", now);
            insertDefaultSetting(statement, "reader_display_mode", "vertical", now);
            insertDefaultSetting(statement, "reader_screen_orientation", "auto", now);
            insertDefaultSetting(statement, "reader_brightness", "-1", now);
            insertDefaultSetting(statement, "reader_keep_screen_on", "true", now);
            insertDefaultSetting(statement, "reader_volume_navigation", "false", now);
            insertDefaultSetting(statement, "reader_auto_show_toolbar_at_end", "true", now);
            statement.executeBatch();
        }
    }

    private static void insertDefaultSetting(
            PreparedStatement statement,
            String key,
            String value,
            long updatedAt
    ) throws SQLException {
        statement.setString(1, key);
        statement.setString(2, value);
        statement.setLong(3, updatedAt);
        statement.addBatch();
    }

    public synchronized Connection connection() {
        if (connection == null) {
            throw new IllegalStateException("Desktop database is not open");
        }
        try {
            if (connection.isClosed()) {
                throw new IllegalStateException("Desktop database is closed");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to inspect Desktop database", exception);
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
