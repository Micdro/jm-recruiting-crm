package com.janemichael.jmrecruitingcrm.desktop;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * The desktop app has no console window, so a failed start is written to a file and shown in a
 * message box instead of disappearing silently.
 */
public final class DesktopStartupError {

    private DesktopStartupError() {
    }

    public static void report(Throwable problem) {
        StringWriter trace = new StringWriter();
        problem.printStackTrace(new PrintWriter(trace));
        Path file = DesktopPaths.home().resolve("logs").resolve("startup-error.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, LocalDateTime.now() + System.lineSeparator() + trace);
        } catch (IOException ignored) {
            // Nothing more we can do; the message box below still explains.
        }
        System.err.println(trace);

        if (DesktopOs.isWindows()) {
            // Spring Boot switches AWT to headless while starting; a message box needs it back.
            System.setProperty("java.awt.headless", "false");
        }
        if (!GraphicsEnvironment.isHeadless() && DesktopPaths.setting("jmcrm.no-browser", "JMCRM_NO_BROWSER") == null) {
            Throwable root = problem;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            JOptionPane.showMessageDialog(null,
                    "JM CRM couldn't start.\n\n" + root.getMessage()
                            + "\n\nThe details are in " + file
                            + "\nSend that file to whoever set up the CRM.",
                    "JM CRM", JOptionPane.ERROR_MESSAGE);
        }
    }
}
