package io.github.jukomu.desktop.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** 使用 Desktop 自己的 SQLite 设置表保存标量应用设置。 */
public final class DesktopSettingsStore {
    private final DesktopDatabase database;

    public DesktopSettingsStore(DesktopDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public String getString(String key) throws SQLException {
        Objects.requireNonNull(key, "key");
        synchronized (database) {
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "SELECT value FROM desktop_settings WHERE key = ?")) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            }
        }
    }

    public void putString(String key, String value) throws SQLException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (database) {
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO desktop_settings(key, value, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET
                        value = excluded.value,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }
}
