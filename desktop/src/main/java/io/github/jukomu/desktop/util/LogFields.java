package io.github.jukomu.desktop.util;

public final class LogFields {
    private LogFields() {
    }

    public static String clean(String value) {
        if (value == null || value.isBlank()) return "-";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
        return cleaned.substring(0, Math.min(256, cleaned.length()));
    }
}
