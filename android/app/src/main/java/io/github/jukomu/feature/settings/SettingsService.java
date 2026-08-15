package io.github.jukomu.feature.settings;

import android.content.Context;
import android.util.Log;
import io.github.jukomu.feature.cache.ImageCache;
import io.github.jukomu.feature.download.DownloadService;
import io.github.jukomu.feature.download.api.DownloadTaskReader;
import io.github.jukomu.feature.settings.relocation.DownloadRelocationService;
import io.github.jukomu.feature.settings.relocation.RelocationEventSink;
import io.github.jukomu.platform.permission.PermissionService;
import io.github.jukomu.platform.permission.PermissionState;
import io.github.jukomu.platform.persistence.SettingsStore;
import org.json.JSONObject;

import java.util.List;

/**
 * 设置管理——读写应用设置并协调公开/私有下载目录切换。
 * 纯业务逻辑，不依赖 Capacitor API。
 */
public class SettingsService {

    private static final String TAG = "SettingsService";
    public static final int DEFAULT_CONCURRENCY = 6;
    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 12;

    private final SettingsStore settingsDb;
    private final DownloadTaskReader downloadDb;
    private final DownloadRelocationService relocationService;
    private final PermissionService permissionService;
    private final Context context;
    private final RelocationEventSink relocationEventSink;
    private final ImageCache imageCache;

    public SettingsService(SettingsStore settingsDb, DownloadTaskReader downloadDb,
                           DownloadRelocationService relocationService,
                           PermissionService permissionService,
                           Context context, RelocationEventSink relocationEventSink,
                           ImageCache imageCache) {
        this.settingsDb = settingsDb;
        this.downloadDb = downloadDb;
        this.relocationService = relocationService;
        this.permissionService = permissionService;
        this.context = context;
        this.relocationEventSink = relocationEventSink;
        this.imageCache = imageCache;
    }

    // ---- 设置读写 ----

    public void setDownloadConcurrency(int n) {
        if (n < MIN_CONCURRENCY || n > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("n must be between 1 and 12");
        }
        if (!settingsDb.putString("download_concurrency", String.valueOf(n))) {
            throw new IllegalStateException("保存下载并发设置失败");
        }
    }

    public void setPreloadConcurrency(int n) {
        if (n < MIN_CONCURRENCY || n > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("n must be between 1 and 12");
        }
        if (!settingsDb.putString("preload_concurrency", String.valueOf(n))) {
            throw new IllegalStateException("保存预载并发设置失败");
        }
    }

    public boolean getDownloadPublic() {
        return settingsDb.getBoolean("download_public", false);
    }

    public JSONObject getAllSettings() {
        JSONObject ret = new JSONObject();
        try {
            ret.put("readerPreloadPages", settingsDb.getInt("reader_preload_pages", 15));
            ret.put("preloadConcurrency", normalizeConcurrency(
                settingsDb.getInt("preload_concurrency", DEFAULT_CONCURRENCY)));
            ret.put("downloadConcurrency", normalizeConcurrency(
                settingsDb.getInt("download_concurrency", DEFAULT_CONCURRENCY)));
            ret.put("downloadPublic", settingsDb.getBoolean("download_public", false));
            ImageCache.CacheStats cacheStats = imageCache.getStats();
            // 旧字段继续表示实际容量；新前端通过显式字段区分用户设置和当前上限。
            ret.put("cacheCapacityMb", cacheStats.effectiveMb);
            ret.put("cacheRequestedMb", cacheStats.requestedMb);
            ret.put("cacheEffectiveMb", cacheStats.effectiveMb);
            ret.put("cacheMaxHeapMb", cacheStats.maxHeapMb);
            ret.put("cacheTemporaryClamp", cacheStats.temporaryClamp);
            ret.put("cacheLimitReason", cacheStats.reason);
            ret.put("ocrEnabled", settingsDb.getBoolean("ocr_enabled", true));
            ret.put("readerDisplayMode", getReaderDisplayMode());
            ret.put("readerScreenOrientation", getReaderScreenOrientation());
            ret.put("readerBrightness", getReaderBrightness());
            ret.put("readerKeepScreenOn", getReaderKeepScreenOn());
            ret.put("readerVolumeNavigation", getReaderVolumeNavigation());
            ret.put("readerAutoShowToolbarAtEnd", getReaderAutoShowToolbarAtEnd());
        } catch (Exception e) {
            Log.w(TAG, "构建全部设置信息失败", e);
        }
        return ret;
    }

    public static int normalizeConcurrency(int value) {
        return value < MIN_CONCURRENCY || value > MAX_CONCURRENCY
            ? DEFAULT_CONCURRENCY : value;
    }

    public void setReaderPreloadPages(int n) {
        if (n < 5 || n > 50) {
            throw new IllegalArgumentException("n must be between 5 and 50");
        }
        settingsDb.putString("reader_preload_pages", String.valueOf(n));
    }

    public boolean getOcrEnabled() {
        return settingsDb.getBoolean("ocr_enabled", true);
    }

    public void setOcrEnabled(boolean enabled) {
        settingsDb.putString("ocr_enabled", String.valueOf(enabled));
    }

    // ---- 阅读器设置 ----

    public String getReaderDisplayMode() {
        String raw = settingsDb.getString("reader_display_mode");
        return (raw != null && !raw.isEmpty()) ? raw : "vertical";
    }

    public void setReaderDisplayMode(String mode) {
        if (!"vertical".equals(mode) && !"horizontal".equals(mode)) {
            throw new IllegalArgumentException("mode must be vertical or horizontal");
        }
        settingsDb.putString("reader_display_mode", mode);
    }

    public String getReaderScreenOrientation() {
        String raw = settingsDb.getString("reader_screen_orientation");
        return (raw != null && !raw.isEmpty()) ? raw : "auto";
    }

    public void setReaderScreenOrientation(String orientation) {
        if (!"auto".equals(orientation) && !"portrait".equals(orientation) && !"landscape".equals(orientation)) {
            throw new IllegalArgumentException("orientation must be auto, portrait, or landscape");
        }
        settingsDb.putString("reader_screen_orientation", orientation);
    }

    public float getReaderBrightness() {
        String raw = settingsDb.getString("reader_brightness");
        if (raw == null) return -1f;
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    public void setReaderBrightness(float brightness) {
        if (brightness < -1f || brightness > 1f) {
            throw new IllegalArgumentException("brightness must be between -1 and 1");
        }
        settingsDb.putString("reader_brightness", String.valueOf(brightness));
    }

    public boolean getReaderKeepScreenOn() {
        return settingsDb.getBoolean("reader_keep_screen_on", true);
    }

    public void setReaderKeepScreenOn(boolean enabled) {
        settingsDb.putString("reader_keep_screen_on", String.valueOf(enabled));
    }

    public boolean getReaderVolumeNavigation() {
        return settingsDb.getBoolean("reader_volume_navigation", false);
    }

    public void setReaderVolumeNavigation(boolean enabled) {
        settingsDb.putString("reader_volume_navigation", String.valueOf(enabled));
    }

    public boolean getReaderAutoShowToolbarAtEnd() {
        return settingsDb.getBoolean("reader_auto_show_toolbar_at_end", true);
    }

    public void setReaderAutoShowToolbarAtEnd(boolean enabled) {
        settingsDb.putString("reader_auto_show_toolbar_at_end", String.valueOf(enabled));
    }

    // ---- 下载公开/私有切换 ----

    /**
     * 校验是否可以切换（权限 + 非终态任务检查）。
     *
     * @return null 表示校验通过；非 null 为错误消息。
     */
    public String validateSwitch(boolean open) {
        if (!open) return null;

        PermissionState state = permissionService.checkState(context);
        if (!state.granted) {
            if ("not_supported".equals(state.permissionType)) {
                return "Android 10 不支持公开下载。";
            }
            if ("MANAGE_EXTERNAL_STORAGE".equals(state.permissionType)) {
                return "需要\"所有文件访问权限\"。请先在系统设置中授权后重试。";
            }
            return "需要存储权限。请先调用 requestManageStorage 授权。";
        }

        List<JSONObject> tasks = downloadDb.getAllTasks();
        for (JSONObject t : tasks) {
            String s = t.optString("status");
            if (!DownloadService.STATUS_COMPLETED.equals(s) && !DownloadService.STATUS_FAILED.equals(s)) {
                return "有下载任务未完成，请等待全部完成或取消后再切换";
            }
        }
        return null;
    }

    /**
     * 执行文件搬迁（后台线程），并发布搬迁进度。
     */
    public void relocate(boolean open, RelocateCallback callback) {
        Thread t = new Thread(() -> {
            try {
                int moved = relocationService.relocate(open,
                    (current, total, phase, currentFile) -> {
                        if (relocationEventSink != null) {
                            relocationEventSink.onRelocationProgress(
                                current, total, phase, currentFile);
                        }
                    });

                settingsDb.putString("download_public", String.valueOf(open));

                JSONObject ret = new JSONObject();
                ret.put("success", true);
                ret.put("downloadPublic", open);
                ret.put("moved", moved);
                callback.onSuccess(ret);
            } catch (Exception e) {
                callback.onError(e.getMessage(), e);
            }
        }, "relocation-worker");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 搬迁结果回调——SettingsService 不用 ApiCallback，因为搬迁可能产生中间进度事件。
     */
    public interface RelocateCallback {
        void onSuccess(JSONObject result);

        void onError(String message, Exception e);
    }
}
