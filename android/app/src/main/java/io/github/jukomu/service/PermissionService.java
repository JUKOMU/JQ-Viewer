package io.github.jukomu.service;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

/**
 * 存储权限检查与请求——根据 API 版本适配不同权限模型。
 * 纯逻辑，不依赖 Capacitor API。
 */
public class PermissionService {

    public static final int REQUEST_WRITE_STORAGE = 1001;

    /**
     * 检查当前存储权限状态（无副作用）。
     */
    public PermissionState checkState(Context ctx) {
        int apiLevel = Build.VERSION.SDK_INT;
        if (apiLevel >= Build.VERSION_CODES.R) {
            boolean granted = Environment.isExternalStorageManager();
            return new PermissionState(granted, "MANAGE_EXTERNAL_STORAGE", apiLevel);
        } else if (apiLevel >= Build.VERSION_CODES.Q) {
            return new PermissionState(false, "not_supported", apiLevel);
        } else if (apiLevel >= Build.VERSION_CODES.M) {
            int check = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            boolean granted = (check == PackageManager.PERMISSION_GRANTED);
            return new PermissionState(granted, "WRITE_EXTERNAL_STORAGE", apiLevel);
        } else {
            return new PermissionState(true, "install_time", apiLevel);
        }
    }

    /**
     * 打开系统"所有文件访问权限"设置页（API 30+）。
     *
     * @throws Exception 无法打开设置页时抛出
     */
    public void openSystemSettings(Context ctx) throws Exception {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + ctx.getPackageName()));
        ctx.startActivity(intent);
    }

    /**
     * 解读 ActivityCompat.requestPermissions 的回调结果。
     */
    public PermissionState interpretResult(int[] grantResults) {
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        return new PermissionState(granted, "WRITE_EXTERNAL_STORAGE", Build.VERSION.SDK_INT);
    }
}
