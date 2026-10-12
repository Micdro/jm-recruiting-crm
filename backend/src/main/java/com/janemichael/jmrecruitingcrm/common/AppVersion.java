package com.janemichael.jmrecruitingcrm.common;

/**
 * Which build of the Windows app this is. CI numbers every build and passes the number to the app
 * ({@code -Djmcrm.build}); a copy run any other way (a server, or during development) has none.
 */
public final class AppVersion {

    private AppVersion() {
    }

    /** The build number, or null when this isn't a numbered build of the Windows app. */
    public static Integer build() {
        String value = System.getProperty("jmcrm.build");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int build = Integer.parseInt(value.trim());
            return build > 0 ? build : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** How a build is shown to people, for example "1.0.57". */
    public static String name(Integer build) {
        return build == null ? null : "1.0." + build;
    }
}
