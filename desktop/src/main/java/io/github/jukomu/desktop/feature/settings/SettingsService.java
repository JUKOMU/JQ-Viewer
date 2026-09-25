package io.github.jukomu.desktop.feature.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.image.CacheCapacityPolicy;
import io.github.jukomu.desktop.feature.settings.model.DownloadLocation;
import io.github.jukomu.desktop.feature.settings.model.ExportFolder;
import io.github.jukomu.desktop.feature.settings.model.ExportPreferencesResponse;
import io.github.jukomu.desktop.feature.settings.model.SettingsResponse;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 持久化页面需要的基础设置，并返回当前支持状态。 */
public final class SettingsService {
    public static final int DEFAULT_CONCURRENCY = 6;
    public static final String DEFAULT_EXPORT_DIRECTORY_TEMPLATE = "{id}";
    public static final String DEFAULT_EXPORT_FILE_NAME_TEMPLATE =
            "【{author}】{title}_{id} {chapterRange}";
    public static final int DEFAULT_CACHE_CAPACITY_MB =
            Math.toIntExact(CacheCapacityPolicy.DEFAULT_REQUESTED_MB);
    private static final String CACHE_CAPACITY = "cache_capacity_mb";
    private static final String EXPORT_FOLDER_KEY = "pdf_export_folder";
    private static final String EXPORT_DIRECTORY_TEMPLATE_KEY = "pdf_export_directory_template";
    private static final String EXPORT_FILE_NAME_TEMPLATE_KEY = "pdf_export_file_name_template";
    private static final String EXPORT_LAST_FORMAT = "export_last_format";
    private static final String DOWNLOAD_PUBLIC = "download_public";
    private static final String DOWNLOAD_FOLDER_REF = "download_folder_ref";
    private static final String DOWNLOAD_DISPLAY_PATH = "download_display_path";
    private static final String DOWNLOAD_PENDING_CLEANUP_FOLDER_REF =
            "download_pending_cleanup_folder_ref";
    private static final String OCR_ENABLED = "ocr_enabled";

    private final Connection connection;
    private final ObjectMapper mapper;

    public SettingsService(Database database) {
        this(database, new ObjectMapper());
    }

    public SettingsService(Database database, ObjectMapper mapper) {
        this.connection = database.openIsolatedConnection();
        this.mapper = mapper;
    }

    public synchronized SettingsResponse all() {
        CacheCapacityPolicy.Result cacheCapacity = new CacheCapacityPolicy().calculate(
                cacheCapacityMb(), Runtime.getRuntime().maxMemory());
        return new SettingsResponse(
                integer("reader_preload_pages", 15),
                preloadConcurrency(),
                downloadConcurrency(),
                downloadLocation().downloadPublic(),
                Math.toIntExact(cacheCapacity.effectiveMb()),
                Math.toIntExact(cacheCapacity.requestedMb()),
                Math.toIntExact(cacheCapacity.effectiveMb()),
                cacheCapacity.maxHeapMb(),
                cacheCapacity.temporaryClamp(),
                cacheCapacity.reason(),
                bool(OCR_ENABLED, true),
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

    public synchronized int cacheCapacityMb() {
        int value = integer(CACHE_CAPACITY, DEFAULT_CACHE_CAPACITY_MB);
        return value < 64 || value > 1024 ? DEFAULT_CACHE_CAPACITY_MB : value;
    }

    public synchronized void setCacheCapacityMb(int value) {
        if (value < 64 || value > 1024) {
            throw ApiException.invalidRequest("mb must be between 64 and 1024");
        }
        put(CACHE_CAPACITY, value);
    }

    public synchronized DownloadLocation downloadLocation() {
        if (!bool(DOWNLOAD_PUBLIC, false)) return new DownloadLocation(false, null, null);
        String folderRef = text(DOWNLOAD_FOLDER_REF, null);
        String displayPath = text(DOWNLOAD_DISPLAY_PATH, null);
        if (folderRef == null || displayPath == null || displayPath.isBlank()) {
            return new DownloadLocation(false, null, null);
        }
        try {
            FileReferences.parseFolder(folderRef);
            return new DownloadLocation(true, folderRef, displayPath);
        } catch (ApiException exception) {
            return new DownloadLocation(false, null, null);
        }
    }

    public synchronized Path downloadRoot(Path privateRoot) {
        DownloadLocation location = downloadLocation();
        return location.downloadPublic()
                ? FileReferences.parseFolder(location.folderRef())
                : privateRoot.toAbsolutePath().normalize();
    }

    public synchronized Path pendingDownloadCleanup() {
        String folderRef = text(DOWNLOAD_PENDING_CLEANUP_FOLDER_REF, null);
        if (folderRef == null) return null;
        try {
            return FileReferences.parseFolder(folderRef);
        } catch (ApiException exception) {
            return null;
        }
    }

    public synchronized void setDownloadLocation(
            boolean downloadPublic,
            Path root,
            Path pendingCleanup
    ) {
        Path normalized = root.toAbsolutePath().normalize();
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                put(connection, DOWNLOAD_PUBLIC, downloadPublic);
                if (downloadPublic) {
                    put(connection, DOWNLOAD_FOLDER_REF, FileReferences.folderRef(normalized));
                    put(connection, DOWNLOAD_DISPLAY_PATH, normalized.toString());
                } else {
                    delete(connection, DOWNLOAD_FOLDER_REF);
                    delete(connection, DOWNLOAD_DISPLAY_PATH);
                }
                if (pendingCleanup == null) {
                    delete(connection, DOWNLOAD_PENDING_CLEANUP_FOLDER_REF);
                } else {
                    put(connection, DOWNLOAD_PENDING_CLEANUP_FOLDER_REF,
                            FileReferences.folderRef(
                                    pendingCleanup.toAbsolutePath().normalize()));
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) throw sqlException;
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new SQLException(exception);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存下载目录失败", exception);
        }
    }

    public synchronized void clearPendingDownloadCleanup() {
        delete(DOWNLOAD_PENDING_CLEANUP_FOLDER_REF);
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

    public synchronized SuccessResponse setOcrEnabled(boolean value) {
        put(OCR_ENABLED, value);
        return SuccessResponse.ok();
    }

    public synchronized ExportPreferencesResponse exportPreferences() {
        return new ExportPreferencesResponse(
                exportFolder(),
                textOrDefault(EXPORT_DIRECTORY_TEMPLATE_KEY, DEFAULT_EXPORT_DIRECTORY_TEMPLATE),
                textOrDefault(EXPORT_FILE_NAME_TEMPLATE_KEY, DEFAULT_EXPORT_FILE_NAME_TEMPLATE),
                exportLastFormat()
        );
    }

    public synchronized SuccessResponse setExportFolder(ExportFolder folder) {
        if (folder == null) {
            delete(EXPORT_FOLDER_KEY);
            return SuccessResponse.ok();
        }
        String folderRef = requiredText(folder.folderRef(), "folderRef");
        String displayPath = requiredText(folder.displayPath(), "displayPath");
        FileReferences.parseFolder(folderRef);
        try {
            put(EXPORT_FOLDER_KEY, mapper.writeValueAsString(
                    new ExportFolder(folderRef, displayPath)));
            return SuccessResponse.ok();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存导出目录失败", exception);
        }
    }

    public synchronized SuccessResponse setExportDirectoryTemplate(String template) {
        setOptionalText(EXPORT_DIRECTORY_TEMPLATE_KEY, template);
        return SuccessResponse.ok();
    }

    public synchronized SuccessResponse setExportFileNameTemplate(String template) {
        setOptionalText(EXPORT_FILE_NAME_TEMPLATE_KEY, template);
        return SuccessResponse.ok();
    }

    public synchronized SuccessResponse setExportLastFormat(String format) {
        if (!"pdf".equals(format) && !"cbz".equals(format) && !"zip".equals(format)) {
            throw ApiException.invalidRequest("format必须是pdf、cbz或zip");
        }
        put(EXPORT_LAST_FORMAT, format);
        return SuccessResponse.ok();
    }

    private String exportLastFormat() {
        String format = text(EXPORT_LAST_FORMAT, "pdf");
        return "cbz".equals(format) || "zip".equals(format) ? format : "pdf";
    }

    private ExportFolder exportFolder() {
        String value = text(EXPORT_FOLDER_KEY, null);
        if (value == null) return null;
        try {
            ExportFolder folder = mapper.readValue(value, ExportFolder.class);
            FileReferences.parseFolder(folder.folderRef());
            requiredText(folder.displayPath(), "displayPath");
            return folder;
        } catch (JsonProcessingException | ApiException exception) {
            return null;
        }
    }

    private void setOptionalText(String key, String value) {
        if (value == null) delete(key);
        else put(key, value);
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

    private String textOrDefault(String key, String fallback) {
        String value = text(key, null);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String text(String key, String fallback) {
        try (PreparedStatement statement = connection
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
        try {
            put(connection, key, value);
        } catch (SQLException exception) {
            throw new IllegalStateException("保存设置失败", exception);
        }
    }

    private static void put(Connection connection, String key, Object value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO settings(key, value) VALUES (?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, String.valueOf(value));
            statement.executeUpdate();
        }
    }

    private void delete(String key) {
        try {
            delete(connection, key);
        } catch (SQLException exception) {
            throw new IllegalStateException("删除设置失败", exception);
        }
    }

    private static void delete(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("DELETE FROM settings WHERE key = ?")) {
            statement.setString(1, key);
            statement.executeUpdate();
        }
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw badRequest(name + "不能为空");
        return value;
    }

    private static ApiException badRequest(String message) {
        return ApiException.invalidRequest(message);
    }
}
