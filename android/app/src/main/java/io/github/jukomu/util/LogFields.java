package io.github.jukomu.util;

public final class LogFields {
    private LogFields() {
    }

    public static String clean(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), 256));
        for (int i = 0; i < value.length() && out.length() < 256; i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString();
    }
}
