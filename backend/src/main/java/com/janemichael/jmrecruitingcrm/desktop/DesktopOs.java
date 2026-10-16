package com.janemichael.jmrecruitingcrm.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The few things the desktop copy asks of the operating system: a browser, shortcuts, the firewall. */
public final class DesktopOs {

    private static final Logger log = LoggerFactory.getLogger(DesktopOs.class);

    public static final String APP_NAME = "JM CRM";
    public static final String FIREWALL_RULE = "JM CRM direct sync";
    /** The rule older versions added, which only let Tailscale devices in. */
    private static final String OLD_FIREWALL_RULE = "JM CRM sync";

    private DesktopOs() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    public static void openBrowser(String url) {
        List<String> command;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (isWindows()) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            command = List.of("open", url);
        } else {
            command = List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(command).start();
        } catch (IOException exception) {
            log.info("Open {} in your browser", url);
        }
    }

    /** The installed app's .exe, set by the Windows launcher. Null when run another way. */
    public static String executable() {
        String path = System.getProperty("jpackage.app-path");
        return path == null || path.isBlank() ? null : path;
    }

    /** Start menu and desktop shortcuts, made once after install. */
    public static void createShortcuts() {
        String exe = executable();
        if (!isWindows() || exe == null) {
            return;
        }
        waitFor(runPowerShell(shortcutScript("Programs", exe, "") + shortcutScript("Desktop", exe, ""), false));
    }

    /**
     * After the app moved: points the shortcuts that exist (Start menu, desktop, start with Windows)
     * at it. Ones the person removed stay removed.
     */
    public static void repointShortcuts() {
        String exe = executable();
        if (!isWindows() || exe == null) {
            return;
        }
        waitFor(runPowerShell(existingShortcutScript("Programs", exe, "") + existingShortcutScript("Desktop", exe, "")
                + existingShortcutScript("Startup", exe, "--background"), false));
    }

    public static boolean startsWithWindows() {
        return isWindows() && Files.exists(startupShortcut());
    }

    /** Starts the CRM quietly when the person signs in to Windows, so sync runs all day. */
    public static void setStartsWithWindows(boolean enabled) {
        String exe = executable();
        if (!isWindows() || exe == null) {
            return;
        }
        if (enabled) {
            waitFor(runPowerShell(shortcutScript("Startup", exe, "--background"), false));
        } else {
            try {
                Files.deleteIfExists(startupShortcut());
            } catch (IOException exception) {
                log.warn("Could not remove the startup shortcut", exception);
            }
        }
    }

    /**
     * Asks Windows (with its usual permission prompt) to let the other computers reach the sync port,
     * from the home network or, through the router, from other homes. The port only answers
     * requests signed with the team's key.
     */
    public static void allowSyncThroughFirewall(int port) {
        if (!isWindows()) {
            return;
        }
        String rule = "Remove-NetFirewallRule -DisplayName '" + FIREWALL_RULE + "' -ErrorAction SilentlyContinue; "
                + "Remove-NetFirewallRule -DisplayName '" + OLD_FIREWALL_RULE + "' -ErrorAction SilentlyContinue; "
                + "New-NetFirewallRule -DisplayName '" + FIREWALL_RULE + "' -Direction Inbound -Protocol TCP "
                + "-LocalPort " + port + " -Action Allow -Profile Any";
        runPowerShell(rule, true);
    }

    public static boolean firewallAllowsSync() {
        if (!isWindows()) {
            return true;
        }
        Process process = runPowerShell(
                "if (Get-NetFirewallRule -DisplayName '" + FIREWALL_RULE + "' -ErrorAction SilentlyContinue) "
                        + "{ exit 0 } else { exit 1 }", false);
        return waitFor(process) && process.exitValue() == 0;
    }

    private static boolean waitFor(Process process) {
        if (process == null) {
            return false;
        }
        try {
            return process.waitFor(20, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * PostgreSQL drops administrator rights when started from an elevated process ("Run as
     * administrator"), and then may not be allowed to set up a folder that the elevated process
     * created. Granting the signed-in user full control of the app folder, inherited by everything
     * in it, avoids that. A normal start already has these rights, so this changes nothing there.
     */
    public static void grantCurrentUserFullControl(Path folder) {
        String user = System.getenv("USERNAME");
        if (!isWindows() || user == null || user.isBlank()) {
            return;
        }
        String domain = System.getenv("USERDOMAIN");
        String account = domain == null || domain.isBlank() ? user : domain + "\\" + user;
        try {
            Process process = new ProcessBuilder("icacls", folder.toString(), "/grant", account + ":(OI)(CI)F", "/Q")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            waitFor(process);
        } catch (IOException exception) {
            log.warn("Could not set permissions on {}", folder, exception);
        }
    }

    private static Path startupShortcut() {
        String appData = System.getenv("APPDATA");
        return Path.of(appData == null ? "" : appData,
                "Microsoft", "Windows", "Start Menu", "Programs", "Startup", APP_NAME + ".lnk");
    }

    private static String shortcutScript(String folder, String exe, String arguments) {
        String directory = Path.of(exe).getParent() == null ? "" : Path.of(exe).getParent().toString();
        return "$s = (New-Object -ComObject WScript.Shell).CreateShortcut("
                + "(Join-Path ([Environment]::GetFolderPath('" + folder + "')) '" + APP_NAME + ".lnk')); "
                + "$s.TargetPath = '" + quote(exe) + "'; "
                + "$s.Arguments = '" + quote(arguments) + "'; "
                + "$s.WorkingDirectory = '" + quote(directory) + "'; "
                + "$s.IconLocation = '" + quote(exe) + "'; "
                + "$s.Save(); ";
    }

    private static String existingShortcutScript(String folder, String exe, String arguments) {
        return "if (Test-Path -LiteralPath (Join-Path ([Environment]::GetFolderPath('" + folder + "')) '"
                + APP_NAME + ".lnk')) { " + shortcutScript(folder, exe, arguments) + "} ";
    }

    private static String quote(String value) {
        return value.replace("'", "''");
    }

    /**
     * Starts a PowerShell script on its own, so it keeps running after the CRM closes (the updater
     * waits for that). Returns false if it couldn't be started.
     */
    public static boolean runDetachedPowerShell(String script) {
        if (!isWindows()) {
            return false;
        }
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        List<String> command = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Start-Process powershell.exe -WindowStyle Hidden -ArgumentList "
                        + "'-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand','" + encoded + "'");
        try {
            Process starter = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return starter.waitFor(30, TimeUnit.SECONDS) && starter.exitValue() == 0;
        } catch (IOException exception) {
            log.warn("Could not start PowerShell", exception);
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Runs a PowerShell script passed as -EncodedCommand, so paths with spaces or quotes can't break
     * it. Elevated scripts show Windows' permission prompt.
     */
    private static Process runPowerShell(String script, boolean elevated) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        List<String> command = elevated
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden "
                                + "-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','" + encoded + "'")
                : List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-EncodedCommand", encoded);
        try {
            return new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) {
            log.warn("Could not run PowerShell", exception);
            return null;
        }
    }
}
