package com.janemichael.jmrecruitingcrm.desktop;

import java.nio.file.Path;

/** Where the desktop copy keeps its database, settings and logs, and which ports it uses. */
public final class DesktopPaths {

    public static final int UI_PORT = 4780;
    public static final int SYNC_PORT = 4781;
    public static final int DATABASE_PORT = 4782;

    private DesktopPaths() {
    }

    /**
     * %LOCALAPPDATA%\JMCRM on Windows (private to the Windows user), ~/.jmcrm elsewhere.
     * JMCRM_HOME or -Djmcrm.home overrides it, which tests use to run two copies side by side.
     */
    public static Path home() {
        String override = setting("jmcrm.home", "JMCRM_HOME");
        if (override != null) {
            return Path.of(override);
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "JMCRM");
        }
        return Path.of(System.getProperty("user.home"), ".jmcrm");
    }

    public static int port(String property, String environment, int fallback) {
        String value = setting(property, environment);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? null : value;
    }
}
