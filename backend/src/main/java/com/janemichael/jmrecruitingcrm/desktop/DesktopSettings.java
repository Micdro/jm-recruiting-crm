package com.janemichael.jmrecruitingcrm.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * settings.properties in the desktop home folder: the CRM key and a few one-time flags.
 * The folder belongs to the Windows user, so other accounts on the computer can't read it.
 */
public class DesktopSettings {

    private static final String KEY = "encryption.key";
    private static final String SHORTCUTS = "shortcuts.created";

    private final Path file;
    private final Properties values = new Properties();

    public DesktopSettings(Path home) {
        this.file = home.resolve("settings.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                values.load(in);
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not read " + file, exception);
            }
        }
    }

    public String encryptionKey() {
        String key = values.getProperty(KEY);
        return key == null || key.isBlank() ? null : key.trim();
    }

    public void saveEncryptionKey(String key) {
        values.setProperty(KEY, key);
        save();
    }

    public boolean shortcutsCreated() {
        return Boolean.parseBoolean(values.getProperty(SHORTCUTS, "false"));
    }

    public void markShortcutsCreated() {
        values.setProperty(SHORTCUTS, "true");
        save();
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling("settings.properties.tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                values.store(out, "JM CRM settings. Keep a copy of encryption.key in your password manager.");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not save " + file, exception);
        }
    }
}
