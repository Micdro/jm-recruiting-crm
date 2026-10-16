package com.janemichael.jmrecruitingcrm.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * settings.properties in the desktop home folder: the sync token, the morning reminder and a few
 * one-time flags.
 *
 * <p>The CRM key itself is not kept here. Each account holds a copy locked with its password, in the
 * database. Versions before that stored the key here ({@code encryption.key}); it is removed once
 * someone has signed in and their locked copy exists.
 */
public class DesktopSettings {

    private static final String LEGACY_KEY = "encryption.key";
    private static final String SYNC_TOKEN = "sync.token";
    private static final String SHORTCUTS = "shortcuts.created";
    private static final String SHORTCUTS_TARGET = "shortcuts.target";
    private static final String REMINDER_ENABLED = "reminder.enabled";
    private static final String REMINDER_TIME = "reminder.time";
    private static final String REMINDER_EMAIL = "reminder.email";
    private static final String REMINDER_SHOWN = "reminder.shown";
    public static final LocalTime DEFAULT_REMINDER_TIME = LocalTime.of(8, 30);
    /** Several parts of the app keep their own copy; each change rereads the file so none undoes another's. */
    private static final Object FILE_LOCK = new Object();

    private final Path file;
    private final Properties values = new Properties();

    public DesktopSettings(Path home) {
        this.file = home.resolve("settings.properties");
        load();
    }

    /** The secret this computer shares with the others for syncing. Doesn't unlock any data. */
    public String syncToken() {
        return value(SYNC_TOKEN);
    }

    public void saveSyncToken(String token) {
        change(properties -> properties.setProperty(SYNC_TOKEN, token));
    }

    /** The CRM key saved by an older version, or null. */
    public String legacyEncryptionKey() {
        return value(LEGACY_KEY);
    }

    public void forgetLegacyEncryptionKey() {
        if (values.containsKey(LEGACY_KEY)) {
            change(properties -> properties.remove(LEGACY_KEY));
        }
    }

    public boolean shortcutsCreated() {
        return Boolean.parseBoolean(values.getProperty(SHORTCUTS, "false"));
    }

    /** The .exe the shortcuts point at, or null if a version that didn't record it made them. */
    public String shortcutsTarget() {
        return value(SHORTCUTS_TARGET);
    }

    public void saveShortcutsTarget(String exe) {
        change(properties -> {
            properties.setProperty(SHORTCUTS, "true");
            properties.setProperty(SHORTCUTS_TARGET, exe);
        });
    }

    /** On unless turned off on the This computer page. */
    public boolean reminderEnabled() {
        return !"false".equalsIgnoreCase(values.getProperty(REMINDER_ENABLED, "true").trim());
    }

    public LocalTime reminderTime() {
        String value = value(REMINDER_TIME);
        try {
            return value == null ? DEFAULT_REMINDER_TIME : LocalTime.parse(value);
        } catch (DateTimeParseException unreadable) {
            return DEFAULT_REMINDER_TIME;
        }
    }

    public void saveReminder(boolean enabled, LocalTime time) {
        change(properties -> {
            properties.setProperty(REMINDER_ENABLED, String.valueOf(enabled));
            properties.setProperty(REMINDER_TIME, time.toString());
        });
    }

    /** Whose day the reminder is about: the last person to sign in on this computer. */
    public String reminderEmail() {
        return value(REMINDER_EMAIL);
    }

    public void saveReminderEmail(String email) {
        if (email != null && !email.equalsIgnoreCase(reminderEmail())) {
            change(properties -> properties.setProperty(REMINDER_EMAIL, email));
        }
    }

    /** The day the reminder last showed, so it shows once a day. */
    public LocalDate reminderShownOn() {
        String value = value(REMINDER_SHOWN);
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    public void markReminderShown(LocalDate day) {
        change(properties -> properties.setProperty(REMINDER_SHOWN, day.toString()));
    }

    private String value(String name) {
        String value = values.getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void load() {
        values.clear();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                values.load(in);
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not read " + file, exception);
            }
        }
    }

    /** Applies one change to the file as it is now, not to this copy's view of it. */
    private void change(Consumer<Properties> edit) {
        synchronized (FILE_LOCK) {
            load();
            edit.accept(values);
            save();
        }
    }

    private void save() {
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
