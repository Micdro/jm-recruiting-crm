package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.common.AppVersion;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.sync.SyncCipher.UnreadableException;
import com.janemichael.jmrecruitingcrm.sync.SyncFolder.About;
import com.janemichael.jmrecruitingcrm.sync.SyncFolder.Batch;
import com.janemichael.jmrecruitingcrm.sync.SyncFolder.Belongs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Syncs through a shared folder that a cloud app (Google Drive, OneDrive) keeps the same on every
 * computer. Every few seconds this computer writes what changed here into its own part of the
 * folder and applies whatever the other computers wrote into theirs. The computers never need to
 * reach each other, or be on at the same time.
 */
@Service
public class FolderSync {

    private static final Logger log = LoggerFactory.getLogger(FolderSync.class);

    /** The folder name suggested in Google Drive or OneDrive. */
    public static final String FOLDER_NAME = "JM CRM Sync";
    private static final int CHANGES_PER_FILE = 1000;
    private static final Duration HEARTBEAT = Duration.ofMinutes(2);

    /** Where this computer syncs, and what's wrong with that folder if anything. */
    public record FolderStatus(String path, String problem) {
    }

    /** A folder that looks right for syncing, found on this computer. */
    public record Suggestion(String path, boolean exists, boolean holdsCrm) {
    }

    public record Copy(int computers, int applied, String warning) {
    }

    private final JdbcTemplate jdbc;
    private final SyncIdentity identity;
    private final SyncFeed feed;
    private final SyncApplier applier;
    private final FieldEncryptor encryptor;
    private final DirectSync direct;
    private final int compactAfter;

    /** What was last written to the folder for each row, so a row isn't written again unchanged. */
    private final Map<String, String> written = new HashMap<>();
    private volatile String problem;
    private volatile Instant lastAbout = Instant.EPOCH;
    private volatile List<String> lastAddresses = List.of();

    public FolderSync(
            JdbcTemplate jdbc,
            SyncIdentity identity,
            SyncFeed feed,
            SyncApplier applier,
            FieldEncryptor encryptor,
            DirectSync direct,
            @Value("${app.sync.folder.compact-after:200}") int compactAfter
    ) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.feed = feed;
        this.applier = applier;
        this.encryptor = encryptor;
        this.direct = direct;
        this.compactAfter = Math.max(2, compactAfter);
    }

    @Scheduled(initialDelayString = "${app.sync.initial-delay-ms:3000}",
            fixedDelayString = "${app.sync.folder.interval-ms:${app.sync.interval-ms:5000}}")
    public void scheduled() {
        // Runs while the CRM is locked too: the files only need the sync token.
        if (identity.isEnabled() && encryptor.hasSyncToken() && folderPath() != null) {
            syncNow();
        }
    }

    public FolderStatus status() {
        Path path = folderPath();
        return new FolderStatus(path == null ? null : path.toString(), path == null ? null : problem);
    }

    /** One round: write this computer's changes, then read everyone else's. */
    public synchronized void syncNow() {
        Path path = folderPath();
        if (path == null || !identity.isEnabled() || !encryptor.hasSyncToken()) {
            return;
        }
        SyncFolder folder = new SyncFolder(path, encryptor.syncToken());
        try {
            if (!Files.isDirectory(path)) {
                problem = "Can't find the sync folder. Check that Google Drive (or OneDrive) is running and signed in.";
                return;
            }
            Belongs belongs = folder.belongs();
            if (belongs == Belongs.ANOTHER_CRM) {
                problem = "The sync folder belongs to a CRM with a different key.";
                return;
            }
            if (belongs == Belongs.EMPTY) {
                // Someone emptied it: start it again with everything this computer has.
                folder.claim();
                restartPublishing();
            }
            problem = null;
            publish(folder);
            readOthers(folder);
            syncFiles(folder);
        } catch (IOException exception) {
            log.warn("Could not sync through {}", path, exception);
            problem = "Couldn't use the sync folder: " + exception.getMessage();
        }
    }

    // Choosing the folder -------------------------------------------------------------

    /** Sets (or changes) this computer's sync folder and writes everything here into it. */
    public synchronized void useFolder(String rawPath) {
        if (!identity.isEnabled() || !encryptor.hasSyncToken()) {
            throw new IllegalStateException("Set up this computer first.");
        }
        Path path = prepare(rawPath);
        SyncFolder folder = new SyncFolder(path, encryptor.syncToken());
        try {
            switch (folder.belongs()) {
                case ANOTHER_CRM -> throw new BadRequestException(
                        "That folder is used by a CRM with a different key. Choose another folder.");
                case NOT_READY -> throw new BadRequestException(
                        "That folder is still being copied to this computer. Wait a minute and try again.");
                case EMPTY -> folder.claim();
                case THIS_CRM -> {
                    // Joining it, or coming back to it.
                }
            }
            Files.createDirectories(folder.computerDir(identity.nodeId()));
        } catch (IOException exception) {
            throw new BadRequestException("Couldn't use that folder: " + exception.getMessage());
        }
        jdbc.update("UPDATE sync_self SET folder = ?", path.toString());
        restartPublishing();
        problem = null;
        log.info("Syncing through {}", path);
        syncNow();
    }

    /**
     * Writes everything again (other computers skip what they already have), numbered on from
     * whatever this computer left in the folder before, under a new instance id so the others read
     * this computer's files from the start.
     */
    private void restartPublishing() {
        jdbc.update("UPDATE sync_self SET folder_cursor = 0, folder_seq = 0, folder_instance = ?",
                UUID.randomUUID().toString());
        written.clear();
        lastAbout = Instant.EPOCH;
        lastAddresses = List.of();
    }

    /** Checks that a new CRM can start in this folder: it must not hold another CRM. */
    public Path checkFreeForNewCrm(String rawPath) {
        Path path = prepare(rawPath);
        try {
            if (new SyncFolder(path, "check").belongs() != Belongs.EMPTY) {
                throw new BadRequestException("That folder already holds a CRM. To use it, choose Join the team's CRM.");
            }
        } catch (IOException exception) {
            throw new BadRequestException("Couldn't use that folder: " + exception.getMessage());
        }
        return path;
    }

    /**
     * For a computer joining the team: checks the folder belongs to the CRM with this sync token and
     * copies everything the other computers have written.
     */
    public synchronized Copy copyEverything(String rawPath, String syncToken) {
        Path path = prepare(rawPath);
        SyncFolder folder = new SyncFolder(path, syncToken);
        try {
            switch (folder.belongs()) {
                case ANOTHER_CRM -> throw new BadRequestException(
                        "That folder belongs to a CRM with a different key. Check the key, or the folder.");
                case NOT_READY -> throw new BadRequestException(
                        "That folder is still being copied to this computer. Wait a minute and try again.");
                case EMPTY -> throw new BadRequestException(
                        "There's no CRM in that folder yet. On the first computer, open This computer and choose "
                                + "the sync folder, then share it.");
                case THIS_CRM -> {
                    // Good.
                }
            }
            List<Integer> computers = folder.computers();
            int applied = 0;
            List<String> problems = new ArrayList<>();
            for (int node : computers) {
                ReadResult result = readFrom(folder, node);
                applied += result.applied();
                if (result.problem() != null) {
                    problems.add(result.problem());
                }
            }
            String warning = computers.isEmpty()
                    ? "No computer has written to the folder yet. Changes arrive once one does."
                    : problems.isEmpty() ? null : String.join(" ", problems) + " It will keep trying.";
            return new Copy(computers.size(), applied, warning);
        } catch (IOException exception) {
            throw new BadRequestException("Couldn't read that folder: " + exception.getMessage());
        }
    }

    /** The number for a computer joining: after every computer in the folder and in the copied data. */
    public int nextNodeId(String rawPath) {
        Path path = prepare(rawPath);
        int highest = 0;
        try {
            for (int node : new SyncFolder(path, "list").computers()) {
                highest = Math.max(highest, node);
            }
        } catch (IOException exception) {
            throw new BadRequestException("Couldn't read that folder: " + exception.getMessage());
        }
        Integer known = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM sync_node", Integer.class);
        return Math.max(highest, known == null ? 0 : known) + 1;
    }

    /** Google Drive and OneDrive folders on this computer that could hold the sync folder. */
    public static List<Suggestion> suggestions() {
        Set<Path> bases = new LinkedHashSet<>();
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                bases.add(root.toPath().resolve("My Drive"));
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            bases.add(Path.of(home, "My Drive"));
            bases.add(Path.of(home, "Google Drive", "My Drive"));
        }
        for (String variable : List.of("OneDrive", "OneDriveConsumer", "OneDriveCommercial")) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                bases.add(Path.of(value));
            }
        }

        List<Suggestion> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path base : bases) {
            try {
                if (!Files.isDirectory(base)) {
                    continue;
                }
                Path candidate = base.resolve(FOLDER_NAME);
                if (seen.add(candidate.toString())) {
                    boolean exists = Files.isDirectory(candidate);
                    found.add(new Suggestion(candidate.toString(), exists,
                            exists && Files.exists(candidate.resolve(SyncFolder.CHECK))));
                }
            } catch (InvalidPathException | SecurityException unusable) {
                // Not a drive we can look at.
            }
        }
        // Folders that already hold the CRM first: that's what a joining computer wants.
        found.sort((a, b) -> Boolean.compare(b.holdsCrm(), a.holdsCrm()));
        return found;
    }

    // Writing -----------------------------------------------------------------------

    private void publish(SyncFolder folder) throws IOException {
        int self = identity.nodeId();
        Map<String, Object> state = jdbc.queryForMap(
                "SELECT name, folder_cursor, folder_seq, folder_instance FROM sync_self LIMIT 1");
        long cursor = ((Number) state.get("folder_cursor")).longValue();
        String instance = (String) state.get("folder_instance");
        if (instance == null) {
            instance = UUID.randomUUID().toString();
            jdbc.update("UPDATE sync_self SET folder_instance = ?", instance);
        }

        // Never reuse a number: another computer may already have read that file.
        long seq = ((Number) state.get("folder_seq")).longValue();
        for (long existing : folder.batches(self)) {
            seq = Math.max(seq, existing);
        }
        for (long existing : folder.snapshots(self)) {
            seq = Math.max(seq, existing);
        }

        SyncFeed.Feed changes = feed.since(cursor);
        List<SyncChange> fresh = new ArrayList<>();
        for (SyncChange change : changes.changes()) {
            if (!fingerprint(change).equals(written.get(change.key()))) {
                fresh.add(change);
            }
        }
        for (int start = 0; start < fresh.size(); start += CHANGES_PER_FILE) {
            seq++;
            List<SyncChange> part = List.copyOf(fresh.subList(start, Math.min(fresh.size(), start + CHANGES_PER_FILE)));
            folder.writeBatch(new Batch(SyncFolder.PROTOCOL, self, seq, false, part));
        }
        for (SyncChange change : fresh) {
            written.put(change.key(), fingerprint(change));
        }
        jdbc.update("UPDATE sync_self SET folder_cursor = ?, folder_seq = ?", changes.cursor(), seq);

        if (folder.batches(self).size() > compactAfter) {
            compact(folder, self, seq);
        }
        List<String> addresses = direct.addresses();
        if (!fresh.isEmpty() || !addresses.equals(lastAddresses)
                || Duration.between(lastAbout, Instant.now()).compareTo(HEARTBEAT) >= 0) {
            folder.writeAbout(new About(SyncFolder.PROTOCOL, self, instance, (String) state.get("name"), seq,
                    System.currentTimeMillis(), changes.cursor(), addresses, AppVersion.build()));
            lastAbout = Instant.now();
            lastAddresses = addresses;
        }
    }

    /**
     * Replaces this computer's batches with one file holding every row, so the folder doesn't keep
     * growing. A computer that hadn't read those batches yet reads the snapshot instead.
     */
    private void compact(SyncFolder folder, int self, long seq) throws IOException {
        SyncFeed.Feed everything = feed.since(0);
        folder.writeBatch(new Batch(SyncFolder.PROTOCOL, self, seq, true, everything.changes()));
        folder.deleteReplacedBy(self, seq);
        log.info("Replaced this computer's sync files up to {} with one snapshot of {} rows", seq,
                everything.changes().size());
    }

    private static String fingerprint(SyncChange change) {
        return change.version() + "/" + change.origin() + "/" + change.deleted();
    }

    // Files (resumes and post pictures) ---------------------------------------------------

    /**
     * Candidates' files and posts' pictures travel as files of their own rather than inside the batches, so a large
     * resume isn't copied into every snapshot. This computer fetches the files its records need,
     * shares the ones the folder lacks, and removes files no record uses any more (and that aren't
     * kept for something in the Trash), here and in the folder. The contents stay encrypted with the
     * CRM key throughout, so this works while locked.
     */
    private void syncFiles(SyncFolder folder) throws IOException {
        Set<String> needed = new HashSet<>(jdbc.queryForList("SELECT DISTINCT blob_key FROM candidate_file", String.class));
        needed.addAll(jdbc.queryForList("SELECT image_key FROM content_post WHERE image_key IS NOT NULL", String.class));
        // Files of trashed records too, so a restore on any computer finds them.
        for (String keys : jdbc.queryForList("SELECT blob_keys FROM trash_item WHERE blob_keys IS NOT NULL", String.class)) {
            needed.addAll(List.of(keys.trim().split("\\s+")));
        }
        Set<String> here = new HashSet<>(jdbc.queryForList("SELECT blob_key FROM file_blob", String.class));
        Set<String> shared = folder.fileKeys();

        int fetched = 0;
        for (String key : needed) {
            if (!here.contains(key) && shared.contains(key)) {
                byte[] content = folder.readFile(key);
                if (content != null) {
                    jdbc.update("INSERT INTO file_blob (blob_key, content) VALUES (?, ?) ON CONFLICT DO NOTHING",
                            key, content);
                    here.add(key);
                    fetched++;
                }
            }
        }
        if (fetched > 0) {
            log.info("Fetched {} files from the sync folder", fetched);
        }

        for (String key : here) {
            if (needed.contains(key) && !shared.contains(key)) {
                List<byte[]> content = jdbc.query("SELECT content FROM file_blob WHERE blob_key = ?",
                        (resultSet, row) -> resultSet.getBytes(1), key);
                if (!content.isEmpty()) {
                    folder.writeFile(key, content.getFirst());
                }
            }
        }

        List<String> unused = jdbc.queryForList("""
                SELECT blob_key FROM file_blob b
                WHERE NOT EXISTS (SELECT 1 FROM candidate_file f WHERE f.blob_key = b.blob_key)
                  AND NOT EXISTS (SELECT 1 FROM content_post p WHERE p.image_key = b.blob_key)
                  AND NOT EXISTS (SELECT 1 FROM trash_item t WHERE position(b.blob_key IN coalesce(t.blob_keys, '')) > 0)
                  AND b.created_at < now() - interval '1 minute'
                """, String.class);
        for (String key : unused) {
            folder.deleteFile(key);
            jdbc.update("DELETE FROM file_blob WHERE blob_key = ?", key);
        }
    }

    // Reading -----------------------------------------------------------------------

    private record ReadResult(int applied, String problem) {
    }

    private void readOthers(SyncFolder folder) throws IOException {
        int self = identity.nodeId();
        Set<Long> removed = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT row_id FROM sync_row WHERE table_name = 'sync_node' AND deleted", Long.class));
        for (int node : folder.computers()) {
            if (node != self && !removed.contains((long) node)) {
                ReadResult result = readFrom(folder, node);
                if (result.applied() > 0) {
                    log.info("Synced {} changes from computer {} through the folder", result.applied(), node);
                }
            }
        }
    }

    /** Reads one computer's new files, in order. Stops at a gap and tries again next round. */
    private ReadResult readFrom(SyncFolder folder, int node) throws IOException {
        jdbc.update("""
                INSERT INTO sync_peer_state (node_id, last_attempt) VALUES (?, now())
                ON CONFLICT (node_id) DO UPDATE SET last_attempt = now()
                """, node);
        Map<String, Object> state = jdbc.queryForMap(
                "SELECT folder_seq, folder_instance FROM sync_peer_state WHERE node_id = ?", node);
        long done = ((Number) state.get("folder_seq")).longValue();

        int applied = 0;
        try {
            About about = folder.readAbout(node);
            if (about == null) {
                return recordProblem(node, applied, "Waiting for that computer's first sync files to arrive.");
            }
            if (!about.instance().equals(state.get("folder_instance"))) {
                // A new set of files from that computer: read them all (anything already here is skipped).
                done = 0;
                jdbc.update("UPDATE sync_peer_state SET folder_seq = 0, folder_instance = ? WHERE node_id = ?",
                        about.instance(), node);
            }
            List<String> addresses = about.addresses() == null ? List.of() : about.addresses();
            jdbc.update("UPDATE sync_peer_state SET last_seen = ?, direct_addresses = ?, app_build = ? WHERE node_id = ?",
                    Timestamp.from(Instant.ofEpochMilli(about.updatedAt())),
                    addresses.isEmpty() ? null : String.join(",", addresses), about.build(), node);

            List<Long> batches = folder.batches(node);
            long first = batches.isEmpty() ? Long.MAX_VALUE : batches.get(0);
            if (first > done + 1) {
                // The batches after `done` were folded into a snapshot (or haven't arrived yet).
                long snapshot = -1;
                for (long seq : folder.snapshots(node)) {
                    if (seq > done && (batches.isEmpty() || seq >= first - 1)) {
                        snapshot = Math.max(snapshot, seq);
                    }
                }
                if (snapshot > 0) {
                    applied += applier.apply(folder.readBatch(node, snapshot, true).changes()).applied();
                    done = snapshot;
                    saveRead(node, done);
                } else if (!batches.isEmpty() || done < about.lastBatch()) {
                    return recordProblem(node, applied,
                            "Waiting for Google Drive to finish copying that computer's sync files.");
                }
            }
            for (long seq : batches) {
                if (seq <= done) {
                    continue;
                }
                if (seq != done + 1) {
                    return recordProblem(node, applied,
                            "Waiting for Google Drive to finish copying that computer's sync files.");
                }
                applied += applier.apply(folder.readBatch(node, seq, false).changes()).applied();
                done = seq;
                saveRead(node, done);
            }
            if (done >= about.lastBatch() && about.cursor() > 0) {
                // Everything that computer had made up to that point is here: a direct connection
                // only needs to ask for what's newer.
                jdbc.update("UPDATE sync_peer_state SET cursor = greatest(cursor, ?) WHERE node_id = ?",
                        about.cursor(), node);
            }
            jdbc.update("UPDATE sync_peer_state SET last_success = now(), last_error = NULL WHERE node_id = ?", node);
            return new ReadResult(applied, null);
        } catch (UnreadableException exception) {
            log.info("Sync file from computer {} not readable yet: {}", node, exception.getMessage());
            return recordProblem(node, applied, "A sync file from that computer isn't readable yet. "
                    + "Google Drive may still be copying it.");
        }
    }

    private void saveRead(int node, long seq) {
        jdbc.update("UPDATE sync_peer_state SET folder_seq = ? WHERE node_id = ?", seq, node);
    }

    private ReadResult recordProblem(int node, int applied, String message) {
        jdbc.update("UPDATE sync_peer_state SET last_error = ? WHERE node_id = ?", message, node);
        return new ReadResult(applied, message);
    }

    // Helpers -----------------------------------------------------------------------

    private Path folderPath() {
        List<String> paths = jdbc.queryForList("SELECT folder FROM sync_self WHERE folder IS NOT NULL LIMIT 1",
                String.class);
        return paths.isEmpty() ? null : Path.of(paths.get(0));
    }

    /**
     * Turns what someone typed or pasted (Windows "Copy as path" adds quotes) into a folder path,
     * creating the folder itself if its parent exists.
     */
    static Path prepare(String rawPath) {
        String cleaned = rawPath == null ? "" : rawPath.trim();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.isEmpty()) {
            throw new BadRequestException("Choose the sync folder");
        }
        Path path;
        try {
            path = Path.of(cleaned).toAbsolutePath().normalize();
        } catch (InvalidPathException invalid) {
            throw new BadRequestException("That isn't a folder path: " + cleaned);
        }
        if (Files.isDirectory(path)) {
            return path;
        }
        if (Files.exists(path)) {
            throw new BadRequestException("That's a file, not a folder: " + path);
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new BadRequestException("Can't find " + (parent == null ? path : parent)
                    + ". Check that Google Drive is running and the path is right.");
        }
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new BadRequestException("Couldn't create " + path + ": " + exception.getMessage());
        }
        return path;
    }
}
