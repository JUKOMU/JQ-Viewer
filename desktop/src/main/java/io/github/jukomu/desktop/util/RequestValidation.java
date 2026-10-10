package io.github.jukomu.desktop.util;

import io.github.jukomu.desktop.bridge.ApiException;

public final class RequestValidation {
    private RequestValidation() {
    }

    public static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw ApiException.invalidRequest(name + "不能为空");
        return value;
    }

    public static String requiredTrimmedText(String value, String name) {
        return requiredText(value, name).trim();
    }
}
