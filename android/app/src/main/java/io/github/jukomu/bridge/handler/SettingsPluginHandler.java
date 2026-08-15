package io.github.jukomu.bridge.handler;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import io.github.jukomu.feature.settings.SettingsService;

import org.json.JSONObject;

/**
 * 负责通用设置 Bridge 的参数校验、持久化调用和响应适配。
 */
public final class SettingsPluginHandler {

    private final SettingsService settingsService;

    public SettingsPluginHandler(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * 保存 1 至 12 的下载并发数。
     */
    public void setDownloadConcurrency(PluginCall call) {
        try {
            Integer concurrency = call.getInt("n");
            if (concurrency == null || concurrency < 1 || concurrency > 12) {
                call.reject("n must be between 1 and 12");
                return;
            }
            settingsService.setDownloadConcurrency(concurrency);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 保存 1 至 12 的图片预加载并发数。
     */
    public void setPreloadConcurrency(PluginCall call) {
        try {
            Integer concurrency = call.getInt("n");
            if (concurrency == null || concurrency < 1 || concurrency > 12) {
                call.reject("n must be between 1 and 12");
                return;
            }
            settingsService.setPreloadConcurrency(concurrency);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 校验下载目录切换条件，并异步搬迁已有文件。
     */
    public void setDownloadPublic(PluginCall call) {
        boolean open = call.getBoolean("open", false);

        String validationError = settingsService.validateSwitch(open);
        if (validationError != null) {
            call.reject(validationError);
            return;
        }

        call.setKeepAlive(true);
        settingsService.relocate(open, new SettingsService.RelocateCallback() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    call.resolve(JSObject.fromJSONObject(result));
                } catch (Exception error) {
                    call.reject(error.getMessage(), error);
                }
            }

            @Override
            public void onError(String message, Exception error) {
                call.reject(message, error);
            }
        });
    }

    /**
     * 返回当前是否使用公开下载目录。
     */
    public void getDownloadPublic(PluginCall call) {
        JSObject result = new JSObject();
        result.put("downloadPublic", settingsService.getDownloadPublic());
        call.resolve(result);
    }

    /**
     * 返回应用的通用设置快照。
     */
    public void getAllSettings(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(settingsService.getAllSettings()));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 保存 5 至 50 的阅读器预加载页数。
     */
    public void setReaderPreloadPages(PluginCall call) {
        try {
            Integer pages = call.getInt("n");
            if (pages == null || pages < 5 || pages > 50) {
                call.reject("n must be between 5 and 50");
                return;
            }
            settingsService.setReaderPreloadPages(pages);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 保存 OCR 功能开关；enabled 为必填布尔值。
     */
    public void setOcrEnabled(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            settingsService.setOcrEnabled(enabled);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    private static JSObject successResult() {
        JSObject result = new JSObject();
        result.put("success", true);
        return result;
    }
}
