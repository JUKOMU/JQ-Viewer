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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS desktop_settings (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS browse_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        album_id TEXT NOT NULL,
                        album_title TEXT NOT NULL,
                        cover_url TEXT NOT NULL,
                        authors TEXT NOT NULL,
                        chapter_id TEXT NOT NULL,
                        chapter_title TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_browse_history_timestamp
                    ON browse_history(timestamp DESC, id DESC)
                    """);
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO desktop_schema_version(version) "
                        + "SELECT 2 WHERE NOT EXISTS "
                        + "(SELECT 1 FROM desktop_schema_version)"
        )) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE desktop_schema_version SET version = 2 WHERE version < 2"
        )) {
            statement.executeUpdate();
        }
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
