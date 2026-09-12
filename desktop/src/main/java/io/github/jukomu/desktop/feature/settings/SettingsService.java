package io.github.jukomu.desktop.feature.settings;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;

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

    public synchronized ObjectNode all() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("readerPreloadPages", integer("reader_preload_pages", 15));
        result.put("preloadConcurrency", preloadConcurrency());
        result.put("downloadConcurrency", downloadConcurrency());
        result.put("downloadPublic", false);
        result.put("cacheCapacityMb", CACHE_CAPACITY_MB);
        result.put("cacheRequestedMb", CACHE_CAPACITY_MB);
        result.put("cacheEffectiveMb", CACHE_CAPACITY_MB);
        result.put("cacheMaxHeapMb", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        result.put("cacheTemporaryClamp", false);
        result.put("cacheLimitReason", "");
        result.put("ocrEnabled", false);
        result.put("readerDisplayMode", text("reader_display_mode", "vertical"));
        result.put("readerScreenOrientation", "auto");
        result.put("readerBrightness", -1);
        result.put("readerKeepScreenOn", true);
        result.put("readerVolumeNavigation", false);
        result.put("readerAutoShowToolbarAtEnd", bool("reader_auto_show_toolbar_at_end", true));
        return result;
    }

    public synchronized int preloadConcurrency() {
        return concurrency("preload_concurrency");
    }

    public synchronized int downloadConcurrency() {
        return concurrency("download_concurrency");
    }

    public synchronized ObjectNode setConcurrency(String key, int value) {
        if (value < 1 || value > 12) throw badRequest("n必须在1到12之间");
        put(key, value);
        return JsonNodeFactory.instance.objectNode().put("success", true);
    }

    public synchronized ObjectNode setReaderPreloadPages(int value) {
        if (value < 5 || value > 50) throw badRequest("n必须在5到50之间");
        put("reader_preload_pages", value);
        return JsonNodeFactory.instance.objectNode().put("success", true);
    }

    public synchronized ObjectNode setDisplayMode(String value) {
        if (!"vertical".equals(value) && !"horizontal".equals(value)) {
            throw badRequest("mode必须是vertical或horizontal");
        }
        put("reader_display_mode", value);
        return JsonNodeFactory.instance.objectNode().put("success", true);
    }

    public synchronized ObjectNode setAutoShow(boolean value) {
        put("reader_auto_show_toolbar_at_end", value);
        return JsonNodeFactory.instance.objectNode().put("success", true);
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
        return new ApiException("bad-request", 400, message);
    }
}
