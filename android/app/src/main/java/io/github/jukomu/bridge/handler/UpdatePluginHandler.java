package io.github.jukomu.bridge.handler;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import io.github.jukomu.feature.update.UpdateManifest;
import io.github.jukomu.feature.update.UpdateService;

import android.app.Activity;

import java.util.function.Supplier;

/**
 * 负责应用内更新 Bridge 参数校验和结果适配。
 */
public final class UpdatePluginHandler {

    private final UpdateService updateService;
    private final Supplier<Activity> activitySupplier;

    public UpdatePluginHandler(UpdateService updateService, Supplier<Activity> activitySupplier) {
        this.updateService = updateService;
        this.activitySupplier = activitySupplier;
    }

    /**
     * 获取并校验 GitHub/Gitee 正式版更新元数据。
     */
    public void checkUpdate(PluginCall call) {
        updateService.checkUpdate(result -> {
            if (!result.success) {
                call.reject(result.error);
                return;
            }
            JSObject response = new JSObject();
            response.put("updateAvailable", result.updateAvailable);
            response.put("manifest", manifestToJson(result.manifest));
            call.resolve(response);
        });
    }

    /**
     * 开始最近一次检查确认的双源下载。
     */
    public void startUpdate(PluginCall call) {
        if (!updateService.startUpdate()) {
            call.reject("更新无法开始，请先完成检查并允许通知");
            return;
        }
        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    /**
     * 取消下载并清理更新临时文件。
     */
    public void cancelUpdate(PluginCall call) {
        updateService.cancelUpdate();
        JSObject result = new JSObject();
        result.put("cancelled", true);
        call.resolve(result);
    }

    /**
     * 返回当前原生更新状态快照。
     */
    public void getUpdateState(PluginCall call) {
        call.resolve(updateService.getSnapshot().toJson());
    }

    /**
     * 启动已校验的 APK 安装器，必要时返回未知来源权限提示。
     */
    public void installUpdate(PluginCall call) {
        UpdateService.InstallResult result = updateService.installUpdate(activitySupplier.get());
        if (!result.started && !result.permissionRequired) {
            call.reject(result.error == null ? "无法启动安装器" : result.error);
            return;
        }
        JSObject response = new JSObject();
        response.put("started", result.started);
        response.put("permissionRequired", result.permissionRequired);
        call.resolve(response);
    }

    /**
     * 打开应用的未知来源安装权限设置页。
     */
    public void requestInstallPermission(PluginCall call) {
        JSObject response = new JSObject();
        response.put("requested", updateService.requestInstallPermission(activitySupplier.get()));
        call.resolve(response);
    }

    private JSObject manifestToJson(UpdateManifest manifest) {
        JSObject result = new JSObject();
        result.put("tag", manifest.getTag());
        result.put("versionName", manifest.getVersionName());
        result.put("versionCode", manifest.getVersionCode());
        result.put("packageName", manifest.getPackageName());
        result.put("apkName", manifest.getApkName());
        result.put("sizeBytes", manifest.getSizeBytes());
        result.put("sha256", manifest.getSha256());
        result.put("signingCertificateSha256", manifest.getSigningCertificateSha256());
        result.put("releaseNotes", manifest.getReleaseNotes());
        JSObject sources = new JSObject();
        sources.put("github", manifest.getGithubUrl());
        sources.put("gitee", manifest.getGiteeUrl());
        result.put("sources", sources);
        return result;
    }
}
