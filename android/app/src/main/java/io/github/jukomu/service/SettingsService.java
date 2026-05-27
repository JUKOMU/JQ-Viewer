package io.github.jukomu.service;

import android.content.Context;
import android.util.Log;

import io.github.jukomu.data.DownloadStore;
import io.github.jukomu.data.FileStore;
import io.github.jukomu.data.SettingsStore;

import org.json.JSONObject;

import java.util.List;

/**
 * 设置管理——读写所有应用设置，包含公开/私有目录搬迁逻辑。
 * 纯业务逻辑，不依赖 Capacitor API。
 */
public class SettingsService {

    private static final String TAG = "SettingsService";

    private final SettingsStore settingsDb;
    private final DownloadStore downloadDb;
    private final FileStore fileStore;
    private final PermissionService permissionService;
    private final Context context;
    private final ServiceListener listener;

    public SettingsService(SettingsStore settingsDb, DownloadStore downloadDb,
                           FileStore fileStore, PermissionService permissionService,
                           Context context, ServiceListener listener) {
        this.settingsDb = settingsDb;
        this.downloadDb = downloadDb;
        this.fileStore = fileStore;
        this.permissionService = permissionService;
        this.context = context;
        this.listener = listener;
    }

    // ---- 设置读写 ----

    public void setDownloadConcurrency(int n) {
        if (n < 1 || n > 12) {
            throw new IllegalArgumentException("n must be between 1 and 12");
        }
        settingsDb.putString("download_concurrency", String.valueOf(n));
    }

    public void setPreloadConcurrency(int n) {
        if (n < 1 || n > 12) {
            throw new IllegalArgumentException("n must be between 1 and 12");
        }
        settingsDb.putString("preload_concurrency", String.valueOf(n));
    }

    public boolean getDownloadPublic() {
        return settingsDb.getBoolean("download_public", false);
    }

    public JSONObject getAllSettings() {
        JSONObject ret = new JSONObject();
        try {
            ret.put("readerPreloadPages", settingsDb.getInt("reader_preload_pages", 15));
            ret.put("preloadConcurrency", settingsDb.getInt("preload_concurrency", 6));
            ret.put("downloadConcurrency", settingsDb.getInt("download_concurrency", 6));
            ret.put("downloadPublic", settingsDb.getBoolean("download_public", false));
            ret.put("cacheCapacityMb", settingsDb.getLong("cache_capacity_mb", 640));
            ret.put("ocrEnabled", settingsDb.getBoolean("ocr_enabled", true));
        } catch (Exception e) {
            Log.w(TAG, "构建全部设置信息失败", e);
        }
        return ret;
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
     * 执行文件搬迁（后台线程），通过 ServiceListener 回调进度。
     */
    public void relocate(boolean open, RelocateCallback callback) {
        Thread t = new Thread(() -> {
            try {
                int moved = fileStore.relocate(context, open,
                        (current, total, phase, currentFile) -> {
                            if (listener != null) {
                                listener.onRelocationProgress(current, total, phase, currentFile);
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
