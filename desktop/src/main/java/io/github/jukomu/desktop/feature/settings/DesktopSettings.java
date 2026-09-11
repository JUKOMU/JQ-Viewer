package io.github.jukomu.desktop.feature.settings;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.data.DesktopDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** 持久化 Desktop 可配置项，并返回当前实际生效的设置快照。 */
public final class DesktopSettings {
    public static final int DEFAULT_CONCURRENCY = 6;
    public static final int DEFAULT_READER_PRELOAD_PAGES = 15;

    private final DesktopDatabase database;

    public DesktopSettings(DesktopDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public ObjectNode getAllSettings() throws SQLException {
        return JsonNodeFactory.instance.objectNode()
                .put("readerPreloadPages", integer("reader_preload_pages", DEFAULT_READER_PRELOAD_PAGES))
                .put("preloadConcurrency", concurrency("preload_concurrency"))
                .put("downloadConcurrency", concurrency("download_concurrency"))
                .put("downloadPublic", false)
                .put("cacheCapacityMb", 0)
                .put("ocrEnabled", false)
                .put("readerDisplayMode", text("reader_display_mode", "vertical"))
                .put("readerScreenOrientation", "auto")
                .put("readerBrightness", -1.0)
                .put("readerKeepScreenOn", false)
                .put("readerVolumeNavigation", false)
                .put("readerAutoShowToolbarAtEnd", bool("reader_auto_show_toolbar_at_end", true));
    }

    public ObjectNode setPreloadConcurrency(int value) throws SQLException {
        return setConcurrency("preload_concurrency", value);
    }

    public ObjectNode setDownloadConcurrency(int value) throws SQLException {
        return setConcurrency("download_concurrency", value);
    }

    public ObjectNode setReaderPreloadPages(int value) throws SQLException {
        if (value < 5 || value > 50) {
            throw new IllegalArgumentException("n must be between 5 and 50");
        }
        put("reader_preload_pages", String.valueOf(value));
        return success();
    }

    public ObjectNode setReaderDisplayMode(String value) throws SQLException {
        String mode = value == null ? "" : value.trim();
        if (!mode.equals("vertical") && !mode.equals("horizontal")) {
            throw new IllegalArgumentException("mode must be vertical or horizontal");
        }
        put("reader_display_mode", mode);
        return success();
    }

    public ObjectNode setReaderAutoShowToolbarAtEnd(boolean value) throws SQLException {
        put("reader_auto_show_toolbar_at_end", String.valueOf(value));
        return success();
    }

    private ObjectNode setConcurrency(String key, int value) throws SQLException {
        if (value < 1 || value > 12) {
            throw new IllegalArgumentException("n must be between 1 and 12");
        }
        put(key, String.valueOf(value));
        return success();
    }

    private int concurrency(String key) throws SQLException {
        int value = integer(key, DEFAULT_CONCURRENCY);
        return value >= 1 && value <= 12 ? value : DEFAULT_CONCURRENCY;
    }

    private int integer(String key, int fallback) throws SQLException {
        String value = get(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean bool(String key, boolean fallback) throws SQLException {
        String value = get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private String text(String key, String fallback) throws SQLException {
        String value = get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String get(String key) throws SQLException {
        synchronized (database) {
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "SELECT value FROM desktop_settings WHERE key = ?"
            )) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            }
        }
    }

    private void put(String key, String value) throws SQLException {
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

    private static ObjectNode success() {
        return JsonNodeFactory.instance.objectNode().put("success", true);
    }
}
