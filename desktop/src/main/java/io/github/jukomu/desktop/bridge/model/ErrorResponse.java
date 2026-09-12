package io.github.jukomu.desktop.bridge.model;

/** 返回稳定错误码和可展示消息。 */
public record ErrorResponse(String code, String message) {
}
