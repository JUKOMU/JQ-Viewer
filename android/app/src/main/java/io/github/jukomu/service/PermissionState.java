package io.github.jukomu.service;

/**
 * 权限检查结果——纯数据，不含 Capacitor/Android UI 类型。
 */
public class PermissionState {
    public final boolean granted;
    public final String permissionType;
    public final int apiLevel;

    public PermissionState(boolean granted, String permissionType, int apiLevel) {
        this.granted = granted;
        this.permissionType = permissionType;
        this.apiLevel = apiLevel;
    }
}
