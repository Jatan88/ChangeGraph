package com.changegraph.backend.common;

public final class Text {

    private Text() {
    }

    public static String clamp(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= max) {
            return cleaned;
        }
        return cleaned.substring(0, max);
    }

    public static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
