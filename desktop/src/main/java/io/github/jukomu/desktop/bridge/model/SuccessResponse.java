package io.github.jukomu.desktop.bridge.model;

/** 表示无需附加结果数据的成功操作。 */
public record SuccessResponse(boolean success) {
    public static SuccessResponse ok() {
        return new SuccessResponse(true);
    }
}
