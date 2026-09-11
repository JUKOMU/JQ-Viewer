package io.github.jukomu.desktop.service;

import io.github.jukomu.desktop.data.DesktopSettingsStore;
import io.github.jukomu.desktop.dto.DesktopDtos;

import java.sql.SQLException;
import java.util.Objects;

/** Desktop 基础设置服务；只持久化标量设置，不承载平台专属能力。 */
public final class DesktopSettingsService {
    public static final int DEFAULT_CONCURRENCY = 6;
    public static final int DEFAULT_CACHE_CAPACITY_MB = 256;
    public static final int DEFAULT_READER_PRELOAD_PAGES = 15;

    private final DesktopSettingsStore store;

    public DesktopSettingsService(DesktopSettingsStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public DesktopDtos.AllSettings getAllSettings() throws SQLException {
        return new DesktopDtos.AllSettings(
                intValue("reader_preload_pages", DEFAULT_READER_PRELOAD_PAGES),
                concurrency("preload_concurrency"),
                concurrency("download_concurrency"),
                booleanValue("download_public", false),
                intValue("cache_capacity_mb", DEFAULT_CACHE_CAPACITY_MB),
                intValue("cache_capacity_mb", DEFAULT_CACHE_CAPACITY_MB),
                intValue("cache_capacity_mb", DEFAULT_CACHE_CAPACITY_MB),
                false,
                "requested-limit",
                booleanValue("ocr_enabled", true),
                textValue("reader_display_mode", "vertical"),
                textValue("reader_screen_orientation", "auto"),
                doubleValue("reader_brightness", -1.0),
                booleanValue("reader_keep_screen_on", true),
                booleanValue("reader_volume_navigation", false),
                booleanValue("reader_auto_show_toolbar_at_end", true)
        );
    }

    public void setPreloadConcurrency(Integer value) throws SQLException {
        putConcurrency("preload_concurrency", value);
    }

    public void setDownloadConcurrency(Integer value) throws SQLException {
        putConcurrency("download_concurrency", value);
    }

    public void setReaderPreloadPages(Integer value) throws SQLException {
        if (value == null || value < 5 || value > 50) {
            throw new IllegalArgumentException("n must be between 5 and 50");
        }
        store.putString("reader_preload_pages", String.valueOf(value));
    }

    public void setReaderDisplayMode(String value) throws SQLException {
        String mode = requireText(value, "mode");
        if (!mode.equals("vertical") && !mode.equals("horizontal")) {
            throw new IllegalArgumentException("mode must be vertical or horizontal");
        }
        store.putString("reader_display_mode", mode);
    }

    public void setReaderAutoShowToolbarAtEnd(Boolean value) throws SQLException {
        if (value == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        store.putString("reader_auto_show_toolbar_at_end", String.valueOf(value));
    }

    private void putConcurrency(String key, Integer value) throws SQLException {
        if (value == null || value < 1 || value > 12) {
            throw new IllegalArgumentException("n must be between 1 and 12");
        }
        store.putString(key, String.valueOf(value));
    }

    private int concurrency(String key) throws SQLException {
        int value = intValue(key, DEFAULT_CONCURRENCY);
        return value < 1 || value > 12 ? DEFAULT_CONCURRENCY : value;
    }

    private int intValue(String key, int fallback) throws SQLException {
        String value = store.getString(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double doubleValue(String key, double fallback) throws SQLException {
        String value = store.getString(key);
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed >= -1.0 && parsed <= 1.0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean booleanValue(String key, boolean fallback) throws SQLException {
        String value = store.getString(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private String textValue(String key, String fallback) throws SQLException {
        String value = store.getString(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }
}
