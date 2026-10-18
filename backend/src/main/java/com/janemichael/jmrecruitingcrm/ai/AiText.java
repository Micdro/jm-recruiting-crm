package com.janemichael.jmrecruitingcrm.ai;

import java.util.Locale;

/** Tidies what AI writes into the firm's plain style, and reads its answers to fixed choices. */
public final class AiText {

    private AiText() {
    }

    /**
     * No em or en dashes (a range like 2019–2022 keeps a hyphen), no runs of blank lines, trimmed.
     * Never null.
     */
    public static String plain(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?m)^[ \\t]*[\\u2014\\u2013][ \\t]*", "- ")
                .replaceAll("(\\d)\\s*[\\u2013\\u2014]\\s*(\\d)", "$1-$2")
                .replaceAll("\\s*[\\u2014\\u2013]\\s*", ", ")
                .replaceAll(",\\s*,", ",")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** Plain text cut to fit a column, at the last line break or space that fits; null when empty. */
    public static String fit(String text, int max) {
        String value = plain(text);
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        String cut = value.substring(0, max);
        int end = Math.max(cut.lastIndexOf('\n'), cut.lastIndexOf(' '));
        return (end > max / 2 ? cut.substring(0, end) : cut).trim();
    }

    /** One of an enum's values from an answer like "full_time" or "Full time"; null for anything else. */
    public static <E extends Enum<E>> E choice(Class<E> type, String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String name = answer.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }

    /** true for "yes", false for "no", null for anything else. */
    public static Boolean yesNo(String answer) {
        String value = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "yes", "true", "y" -> Boolean.TRUE;
            case "no", "false", "n" -> Boolean.FALSE;
            default -> null;
        };
    }
}
