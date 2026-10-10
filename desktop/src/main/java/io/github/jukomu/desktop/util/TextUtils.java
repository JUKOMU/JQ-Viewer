package io.github.jukomu.desktop.util;

public final class TextUtils {
    private TextUtils() {
    }

    public static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    public static String text(String value) {
        return valueOrEmpty(value);
    }
}
