package com.janemichael.jmrecruitingcrm.common;

/**
 * Readable names for enum values in activity summaries, for example IN_SEQUENCE becomes "In sequence"
 * and AI_FOUND becomes "AI found".
 */
public final class Labels {

    private Labels() {
    }

    public static String of(Enum<?> value) {
        if (value == null) {
            return "None";
        }
        String text = value.name().replace('_', ' ').toLowerCase();
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return text.replaceAll("\\b[Aa]i\\b", "AI");
    }
}
