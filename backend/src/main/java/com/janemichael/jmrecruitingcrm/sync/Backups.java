package com.janemichael.jmrecruitingcrm.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Backups of the CRM's shared records, kept on each computer: every week, before a restore, and
 * whenever someone asks. Each one holds every shared record (companies, contacts, candidates,
 * searches, pipeline, placements, history, templates, settings and the Trash) and the resume files
 * they use, as one gzipped file sealed with AES-256-GCM under a key derived from the sync token.
 * The records inside stay encrypted with the CRM key, as they are in the database.
 *
 * <p>Restoring replaces the shared records with the backup's in one transaction. The changes are
 * versioned like any other, so they reach every computer through sync. The team's accounts aren't
 * part of it (an old backup would bring back old passwords), nor is the prospect finder's key,
 * limits and spending, which stay as they are.
 */
@Service
public class Backups {

    private static final Logger log = LoggerFactory.getLogger(Backups.class);

    static final String EXTENSION = ".jmbak";
    static final Duration EVERY = Duration.ofDays(7);
    /** Backups kept on each computer; older ones are removed. */
    static final int KEEP = 10;
    static final int FORMAT = 1;
    /** Shared tables left out: the computers, and the team's accounts and passwords. */
    static final Set<String> SKIPPED = Set.of("sync_node", "app_user");
    /** Settings left out, so a restore doesn't bring back an old API key or secret, or reset what AI has spent. */
    static final List<String> KEPT_SETTINGS = List.of("prospects.", "ai.", "linkedin.");
    /** Bound into every backup's encryption, so a backup can be renamed or moved and still be read. */
    private static final String SEALED_AS = "jmcrm backup";
    private static final Pattern NAME = Pattern.compile(
            "jmcrm-backup-(\\d{4}-\\d{2}-\\d{2}-\\d{6})-(weekly|manual|before-restore)\\.jmbak");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT);

    public enum Kind {
        WEEKLY("weekly"),
        MANUAL("manual"),
        BEFORE_RESTORE("before-restore");

        final String slug;

        Kind(String slug) {
            this.slug = slug;
        }

        static Kind of(String slug) {
            for (Kind kind : values()) {
                if (kind.slug.equals(slug)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException(slug);
        }
    }

    public record Entry(String name, Kind kind, Instant createdAt, long sizeBytes) {
    }

    /** @param safetyBackup the backup of what was there just before, to undo the restore with */
    public record Restored(String name, Instant backedUpAt, int records, String safetyBackup) {
    }

    /**
     * What's inside a backup file, before sealing.
     *
     * @param createdAt epoch milliseconds
     * @param files     resume files by key, base64, still encrypted with the CRM key
     */
    record Contents(int format, long createdAt, String computer, Map<String, List<Map<String, String>>> tables,
                    Map<String, String> files) {
    }

    private final Path folder;
    private final JdbcTemplate jdbc;
    private final SyncCatalog catalog;
    private final FieldEncryptor encryptor;
    private final SyncIdentity identity;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public Backups(
            @Value("${app.backups.folder:}") String folder,
            @Value("${app.desktop.home:}") String home,
            JdbcTemplate jdbc,
            SyncCatalog catalog,
            FieldEncryptor encryptor,
            SyncIdentity identity,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.folder = folder != null && !folder.isBlank()
                ? Path.of(folder.trim())
                : home != null && !home.isBlank() ? Path.of(home.trim()).resolve("backups") : null;
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.encryptor = encryptor;
        this.identity = identity;
        this.snapshot = new TransactionTemplate(transactionManager);
        // One consistent picture of every table, however long reading takes.
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** False on a server, which relies on its database host's backups instead. */
    public boolean isAvailable() {
        return folder != null;
    }

    public Path folder() {
        return folder;
    }

    // Making backups ----------------------------------------------------------------------

    /** Every hour: makes this week's backup if the last one is a week old, while the CRM is unlocked. */
    @Scheduled(initialDelayString = "${app.backups.initial-delay-ms:600000}",
            fixedDelayString = "${app.backups.interval-ms:3600000}")
    public void weekly() {
        if (!isAvailable() || !encryptor.isConfigured() || !encryptor.hasSyncToken()) {
            return;
        }
        try {
            Instant last = list().stream().map(Entry::createdAt).max(Comparator.naturalOrder()).orElse(null);
            if (last == null || last.plus(EVERY).isBefore(clock.instant())) {
                backUp(Kind.WEEKLY);
            }
        } catch (IOException | RuntimeException problem) {
            log.warn("Could not make this week's backup", problem);
        }
    }

    public synchronized Entry backUp(Kind kind) throws IOException {
        requireAvailable();
        Instant now = clock.instant();
        Contents contents = snapshot.execute(status -> read(now));
        byte[] packed = pack(contents);

        Files.createDirectories(folder);
        String name = "jmcrm-backup-" + STAMP.format(LocalDateTime.ofInstant(now, clock.getZone())) + "-"
                + kind.slug + EXTENSION;
        Path target = folder.resolve(name);
        Path partial = folder.resolve(name + ".partial");
        Files.write(partial, packed);
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        prune();
        log.info("Backed up the CRM to {} ({} bytes)", target, packed.length);
        return entry(target);
    }

    private Contents read(Instant now) {
        Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
        for (String table : tables()) {
            tables.put(table, rows(table));
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (String key : neededFiles()) {
            List<byte[]> content = jdbc.query("SELECT content FROM file_blob WHERE blob_key = ?",
                    (resultSet, row) -> resultSet.getBytes(1), key);
            if (!content.isEmpty()) {
                files.put(key, Base64.getEncoder().encodeToString(content.getFirst()));
            }
        }
        String computer = identity.self().map(SyncIdentity.Self::name).orElse(null);
        return new Contents(FORMAT, now.toEpochMilli(), computer, tables, files);
    }

    /** The shared tables a backup holds, parents before children. */
    static List<String> tables() {
        return SyncTables.ORDER.stream().filter(table -> !SKIPPED.contains(table)).toList();
    }

    private static String where(String table) {
        return table.equals("firm_setting")
                ? KEPT_SETTINGS.stream().map(prefix -> "name NOT LIKE '" + prefix + "%'")
                        .collect(Collectors.joining(" AND ", " WHERE ", ""))
                : "";
    }

    /** Every row, every column as text, exactly as PostgreSQL prints it. */
    private List<Map<String, String>> rows(String table) {
        Map<String, String> columns = catalog.columns(table);
        if (columns.isEmpty()) {
            return List.of();
        }
        String select = columns.keySet().stream().map(column -> column + "::text AS " + column)
                .collect(Collectors.joining(", "));
        return jdbc.query("SELECT " + select + " FROM " + table + where(table) + " ORDER BY id", (resultSet, number) -> {
            Map<String, String> row = new LinkedHashMap<>();
            for (String column : columns.keySet()) {
                row.put(column, resultSet.getString(column));
            }
            return row;
        });
    }

    /** Resume files the records use, the Trash's included. */
    private Set<String> neededFiles() {
        Set<String> needed = new HashSet<>(jdbc.queryForList("SELECT DISTINCT blob_key FROM candidate_file", String.class));
        needed.addAll(jdbc.queryForList("SELECT image_key FROM content_post WHERE image_key IS NOT NULL", String.class));
        for (String keys : jdbc.queryForList("SELECT blob_keys FROM trash_item WHERE blob_keys IS NOT NULL", String.class)) {
            needed.addAll(List.of(keys.trim().split("\\s+")));
        }
        needed.remove("");
        return needed;
    }

    byte[] pack(Contents contents) throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) {
            json.writeValue(gzip, contents);
        }
        return SyncCipher.forBackups(encryptor.syncToken()).seal(SEALED_AS, zipped.toByteArray());
    }

    Contents unpack(byte[] stored) {
        byte[] zipped;
        try {
            zipped = SyncCipher.forBackups(encryptor.syncToken()).open(SEALED_AS, stored);
        } catch (SyncCipher.UnreadableException unreadable) {
            throw new BadRequestException("This backup can't be read: it belongs to a different CRM, or the file is "
                    + "damaged.");
        }
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(zipped))) {
            Contents contents = json.readValue(gzip, new TypeReference<Contents>() { });
            if (contents.format() > FORMAT) {
                throw new BadRequestException("This backup was made by a newer version of the CRM. Update this "
                        + "computer first.");
            }
            return contents;
        } catch (IOException unreadable) {
            throw new BadRequestException("This backup can't be read: the file is damaged.");
        }
    }

    // Listing and keeping ------------------------------------------------------------------

    /** This computer's backups, newest first. */
    public List<Entry> list() throws IOException {
        if (!isAvailable() || !Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<Entry> entries = new ArrayList<>();
            for (Path file : files.toList()) {
                if (NAME.matcher(file.getFileName().toString()).matches()) {
                    entries.add(entry(file));
                }
            }
            entries.sort(Comparator.comparing(Entry::createdAt).reversed().thenComparing(Entry::name));
            return entries;
        }
    }

    private Entry entry(Path file) throws IOException {
        Matcher matcher = NAME.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(file.toString());
        }
        Instant created = LocalDateTime.parse(matcher.group(1), STAMP).atZone(clock.getZone()).toInstant();
        return new Entry(file.getFileName().toString(), Kind.of(matcher.group(2)), created, Files.size(file));
    }

    /** Keeps the newest few, and always the newest backup made before a restore. */
    private void prune() throws IOException {
        List<Entry> entries = list();
        Entry lastSafety = entries.stream().filter(entry -> entry.kind() == Kind.BEFORE_RESTORE).findFirst().orElse(null);
        for (Entry old : entries.stream().skip(KEEP).toList()) {
            if (!old.equals(lastSafety)) {
                Files.deleteIfExists(folder.resolve(old.name()));
            }
        }
    }

    /** A backup's file, by a name from the list (nothing else is accepted). */
    public Path file(String name) {
        requireAvailable();
        if (name == null || !NAME.matcher(name).matches()) {
            throw new NotFoundException("Backup not found");
        }
        Path file = folder.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("Backup not found");
        }
        return file;
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new BadRequestException("Backups are kept by the Windows app. On a server, use the database "
                    + "host's backups.");
        }
    }

    // Restoring ----------------------------------------------------------------------------

    /**
     * Replaces the shared records with the backup's, on every computer. What's here now is backed
     * up first, so the restore can be undone by restoring that.
     */
    public synchronized Restored restore(String name) throws IOException {
        Contents contents = unpack(Files.readAllBytes(file(name)));
        Entry safety = backUp(Kind.BEFORE_RESTORE);
        Integer records = transaction.execute(status -> replace(contents));
        log.info("Restored the backup {} ({} records); the data before is in {}", name, records, safety.name());
        return new Restored(name, Instant.ofEpochMilli(contents.createdAt()), records == null ? 0 : records,
                safety.name());
    }

    /**
     * Deletes the shared records, children first, and inserts the backup's, parents first. Each
     * change is versioned by the sync trigger, so the other computers follow.
     */
    int replace(Contents contents) {
        List<String> tables = tables();
        for (int index = tables.size() - 1; index >= 0; index--) {
            String table = tables.get(index);
            jdbc.update("DELETE FROM " + table + where(table));
        }
        int records = 0;
        for (String table : tables) {
            List<Map<String, String>> rows = contents.tables().getOrDefault(table, List.of());
            if (rows.isEmpty()) {
                continue;
            }
            Map<String, String> types = catalog.columns(table);
            // Columns this version knows; ones added since the backup take their defaults.
            List<String> columns = rows.getFirst().keySet().stream().filter(types::containsKey).toList();
            String insert = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                    + columns.stream().map(column -> "?::" + types.get(column)).collect(Collectors.joining(", ")) + ")";
            List<Object[]> values = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String setting = row.getOrDefault("name", "");
                if (table.equals("firm_setting") && KEPT_SETTINGS.stream().anyMatch(setting::startsWith)) {
                    continue;
                }
                values.add(columns.stream().map(row::get).toArray());
            }
            jdbc.batchUpdate(insert, values);
            records += values.size();
        }
        for (Map.Entry<String, String> file : contents.files().entrySet()) {
            jdbc.update("INSERT INTO file_blob (blob_key, content) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    file.getKey(), Base64.getDecoder().decode(file.getValue()));
        }
        return records;
    }
}
