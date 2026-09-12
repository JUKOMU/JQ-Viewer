package io.github.jukomu.desktop.feature.settings;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.settings.model.SettingsResponse;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 持久化页面需要的基础设置，并返回当前支持状态。 */
public final class SettingsService {
    public static final int DEFAULT_CONCURRENCY = 6;
    private static final int CACHE_CAPACITY_MB = 256;

    private final Database database;

    public SettingsService(Database database) {
        this.database = database;
    }

    public synchronized SettingsResponse all() {
        return new SettingsResponse(
                integer("reader_preload_pages", 15),
                preloadConcurrency(),
                downloadConcurrency(),
                false,
                CACHE_CAPACITY_MB,
                CACHE_CAPACITY_MB,
                CACHE_CAPACITY_MB,
                Runtime.getRuntime().maxMemory() / 1024 / 1024,
                false,
                "",
                false,
                text("reader_display_mode", "vertical"),
                "auto",
                -1,
                true,
                false,
                bool("reader_auto_show_toolbar_at_end", true)
        );
    }

    public synchronized int preloadConcurrency() {
        return concurrency("preload_concurrency");
    }

    public synchronized int downloadConcurrency() {
        return concurrency("download_concurrency");
    }

    public synchronized SuccessResponse setConcurrency(String key, int value) {
        if (value < 1 || value > 12) throw badRequest("n必须在1到12之间");
        put(key, value);
        return SuccessResponse.ok();
    }

    public synchronized SuccessResponse setReaderPreloadPages(int value) {
        if (value < 5 || value > 50) throw badRequest("n必须在5到50之间");
        put("reader_preload_pages", value);
        return SuccessResponse.ok();
    }

    public synchronized SuccessResponse setDisplayMode(String value) {
        if (!"vertical".equals(value) && !"horizontal".equals(value)) {
            throw badRequest("mode必须是vertical或horizontal");
        }
        put("reader_display_mode", value);
        return SuccessResponse.ok();
    }

    public synchronized SuccessResponse setAutoShow(boolean value) {
        put("reader_auto_show_toolbar_at_end", value);
        return SuccessResponse.ok();
    }

    private int concurrency(String key) {
        int value = integer(key, DEFAULT_CONCURRENCY);
        return value < 1 || value > 12 ? DEFAULT_CONCURRENCY : value;
    }

    private int integer(String key, int fallback) {
        String value = text(key, null);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean bool(String key, boolean fallback) {
        String value = text(key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private String text(String key, String fallback) {
        try (PreparedStatement statement = database.connection()
                .prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : fallback;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取设置失败", exception);
        }
    }

    private void put(String key, Object value) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "INSERT INTO settings(key, value) VALUES (?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, String.valueOf(value));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存设置失败", exception);
        }
    }

    private static ApiException badRequest(String message) {
        return ApiException.invalidRequest(message);
    }
}
