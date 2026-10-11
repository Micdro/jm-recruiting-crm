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
 * settings.properties in the desktop home folder: the sync token and a few one-time flags.
 *
 * <p>The CRM key itself is not kept here. Each account holds a copy locked with its password, in the
 * database. Versions before that stored the key here ({@code encryption.key}); it is removed once
 * someone has signed in and their locked copy exists.
 */
public class DesktopSettings {

    private static final String LEGACY_KEY = "encryption.key";
    private static final String SYNC_TOKEN = "sync.token";
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

    /** The secret this computer shares with the others for syncing. Doesn't unlock any data. */
    public String syncToken() {
        return value(SYNC_TOKEN);
    }

    public void saveSyncToken(String token) {
        values.setProperty(SYNC_TOKEN, token);
        save();
    }

    /** The CRM key saved by an older version, or null. */
    public String legacyEncryptionKey() {
        return value(LEGACY_KEY);
    }

    public void forgetLegacyEncryptionKey() {
        if (values.remove(LEGACY_KEY) != null) {
            save();
        }
    }

    public boolean shortcutsCreated() {
        return Boolean.parseBoolean(values.getProperty(SHORTCUTS, "false"));
    }

    public void markShortcutsCreated() {
        values.setProperty(SHORTCUTS, "true");
        save();
    }

    private String value(String name) {
        String value = values.getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling("settings.properties.tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                values.store(out, "JM CRM settings");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not save " + file, exception);
        }
    }
}
