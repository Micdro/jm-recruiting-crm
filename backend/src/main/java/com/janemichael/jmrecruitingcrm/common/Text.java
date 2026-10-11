package com.janemichael.jmrecruitingcrm.common;

/** Small helpers for cleaning user input. */
public final class Text {

    private Text() {
    }

    /** Trims the value and turns blank strings into null. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Lowercases and trims a value for comparisons; null stays null. */
    public static String key(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase();
    }

    /** Normalizes a LinkedIn URL so the same profile matches however it was pasted. */
    public static String linkedinKey(String url) {
        String cleaned = key(url);
        if (cleaned == null) {
            return null;
        }
        String result = cleaned
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "");
        int query = result.indexOf('?');
        if (query >= 0) {
            result = result.substring(0, query);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? null : result;
    }
}
