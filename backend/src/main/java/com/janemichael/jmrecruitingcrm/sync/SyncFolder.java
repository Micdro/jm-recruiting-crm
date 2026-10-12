package com.janemichael.jmrecruitingcrm.sync;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.sync.SyncCipher.UnreadableException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The shared folder (in Google Drive, OneDrive or similar) through which computers sync.
 *
 * <pre>
 * crm.check                   proves which CRM the folder belongs to
 * README.txt                  says what the folder is, for anyone who opens it
 * computer-1/
 *   about.jmcrm               which files computer 1 has written, and when it last ran
 *   00000000000000000007.jmcrm   a batch of changes, numbered from 1
 *   snapshot-00000000000000000006.jmcrm   everything, replacing batches 1 to 6
 * computer-2/ ...
 * files/
 *   0f3c...9a.jmfile          a candidate's file (a resume, say), already encrypted with the CRM key
 * </pre>
 *
 * Each computer writes only in its own folder, so two computers never edit the same file. Files
 * are written under a temporary name and then renamed, so a half-written file is never read; and
 * a file that hasn't fully arrived yet fails its encryption check and is simply read next time.
 */
final class SyncFolder {

    static final int PROTOCOL = 1;
    static final String CHECK = "crm.check";
    static final String README = "README.txt";
    static final String ABOUT = "about.jmcrm";

    private static final Pattern COMPUTER = Pattern.compile("computer-(\\d{1,9})");
    private static final Pattern BATCH = Pattern.compile("(\\d{20})\\.jmcrm");
    private static final Pattern SNAPSHOT = Pattern.compile("snapshot-(\\d{20})\\.jmcrm");
    static final String FILES = "files";
    private static final Pattern FILE = Pattern.compile("([0-9a-f]{32})\\.jmfile");
    private static final Pattern FILE_KEY = Pattern.compile("[0-9a-f]{32}");

    /** Written once by the first computer. Only computers with the same key can open it. */
    record Check(int protocol, String app) {
    }

    /**
     * @param instance  a random id for this set of files; a new one tells readers to start over
     * @param lastBatch the newest batch written
     * @param updatedAt when the computer last wrote this, in milliseconds since 1970 (its own clock)
     * @param cursor    how far into its own records the batches up to {@code lastBatch} go, so a
     *                  computer that has read them can connect directly and ask only for what's newer
     * @param addresses where other computers can connect to it directly, if it accepts connections
     * @param build     the app version it runs (null for a development copy)
     */
    record About(int protocol, int node, String instance, String name, long lastBatch, long updatedAt,
                 long cursor, List<String> addresses, Integer build) {
    }

    /** A batch of changes, or (as a snapshot) every row, replacing all batches up to {@code seq}. */
    record Batch(int protocol, int node, long seq, boolean snapshot, List<SyncChange> changes) {
    }

    enum Belongs {
        /** Holds this CRM's files. */
        THIS_CRM,
        /** Holds files of a CRM with a different key. */
        ANOTHER_CRM,
        /** Holds computers' files but not the check file yet: the cloud app is still copying it. */
        NOT_READY,
        /** Empty, or unrelated files only. */
        EMPTY
    }

    private final Path root;
    private final SyncCipher cipher;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    SyncFolder(Path root, String syncToken) {
        this.root = root;
        this.cipher = SyncCipher.forFolder(syncToken);
    }

    Path root() {
        return root;
    }

    Belongs belongs() throws IOException {
        Path check = root.resolve(CHECK);
        if (Files.exists(check)) {
            try {
                read(check, CHECK, Check.class);
                return Belongs.THIS_CRM;
            } catch (UnreadableException wrongKey) {
                return Belongs.ANOTHER_CRM;
            }
        }
        return computers().isEmpty() ? Belongs.EMPTY : Belongs.NOT_READY;
    }

    /** Marks an empty folder as this CRM's. */
    void claim() throws IOException {
        Files.createDirectories(root);
        write(root.resolve(CHECK), CHECK, new Check(PROTOCOL, "JM CRM"));
        Files.writeString(root.resolve(README), """
                This folder keeps the team's copies of JM CRM in step. Each computer leaves its changes \
                here as encrypted files and picks up the others'.

                Don't edit, move or delete anything in it. Nothing here can be read without the CRM key.
                """, StandardCharsets.UTF_8);
    }

    /** The computers that have written here, by number. */
    List<Integer> computers() throws IOException {
        List<Integer> nodes = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return nodes;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                Matcher matcher = COMPUTER.matcher(entry.getFileName().toString());
                if (matcher.matches() && Files.isDirectory(entry)) {
                    nodes.add(Integer.parseInt(matcher.group(1)));
                }
            }
        }
        Collections.sort(nodes);
        return nodes;
    }

    Path computerDir(int node) {
        return root.resolve("computer-" + node);
    }

    List<Long> batches(int node) throws IOException {
        return numbered(node, BATCH);
    }

    List<Long> snapshots(int node) throws IOException {
        return numbered(node, SNAPSHOT);
    }

    void writeBatch(Batch batch) throws IOException {
        String file = batchFile(batch.seq(), batch.snapshot());
        Files.createDirectories(computerDir(batch.node()));
        write(computerDir(batch.node()).resolve(file), name(batch.node(), file), batch);
    }

    Batch readBatch(int node, long seq, boolean snapshot) throws IOException, UnreadableException {
        String file = batchFile(seq, snapshot);
        Batch batch = read(computerDir(node).resolve(file), name(node, file), Batch.class);
        if (batch.node() != node || batch.seq() != seq || batch.snapshot() != snapshot) {
            throw new UnreadableException(name(node, file) + " doesn't match its name");
        }
        return batch;
    }

    void writeAbout(About about) throws IOException {
        Files.createDirectories(computerDir(about.node()));
        write(computerDir(about.node()).resolve(ABOUT), name(about.node(), ABOUT), about);
    }

    /** Null if the computer hasn't written it yet (or it hasn't arrived). */
    About readAbout(int node) throws IOException, UnreadableException {
        Path file = computerDir(node).resolve(ABOUT);
        if (!Files.exists(file)) {
            return null;
        }
        About about = read(file, name(node, ABOUT), About.class);
        if (about.node() != node) {
            throw new UnreadableException(name(node, ABOUT) + " doesn't match its folder");
        }
        return about;
    }

    /** Removes batches a snapshot replaces, and older snapshots. */
    void deleteReplacedBy(int node, long snapshotSeq) throws IOException {
        for (long seq : batches(node)) {
            if (seq <= snapshotSeq) {
                Files.deleteIfExists(computerDir(node).resolve(batchFile(seq, false)));
            }
        }
        for (long seq : snapshots(node)) {
            if (seq < snapshotSeq) {
                Files.deleteIfExists(computerDir(node).resolve(batchFile(seq, true)));
            }
        }
    }

    // Files ---------------------------------------------------------------------------
    // Shared by all computers: any computer that has a file may write it, and the contents are the
    // same wherever they come from.

    /** The keys of the files in the folder. */
    Set<String> fileKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        Path dir = root.resolve(FILES);
        if (!Files.isDirectory(dir)) {
            return keys;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                Matcher matcher = FILE.matcher(entry.getFileName().toString());
                if (matcher.matches()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        return keys;
    }

    void writeFile(String key, byte[] content) throws IOException {
        Files.createDirectories(root.resolve(FILES));
        writeSealed(filePath(key), fileName(key), content);
    }

    /** The file's contents, or null if it isn't here or hasn't fully arrived yet. */
    byte[] readFile(String key) throws IOException {
        Path file = filePath(key);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return cipher.open(fileName(key), Files.readAllBytes(file));
        } catch (NoSuchFileException | UnreadableException notReadyYet) {
            return null;
        }
    }

    void deleteFile(String key) throws IOException {
        Files.deleteIfExists(filePath(key));
    }

    private Path filePath(String key) {
        if (key == null || !FILE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a file key: " + key);
        }
        return root.resolve(FILES).resolve(key + ".jmfile");
    }

    private static String fileName(String key) {
        return FILES + "/" + key + ".jmfile";
    }

    private List<Long> numbered(int node, Pattern pattern) throws IOException {
        List<Long> numbers = new ArrayList<>();
        Path dir = computerDir(node);
        if (!Files.isDirectory(dir)) {
            return numbers;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                Matcher matcher = pattern.matcher(entry.getFileName().toString());
                if (matcher.matches()) {
                    numbers.add(Long.parseLong(matcher.group(1)));
                }
            }
        }
        Collections.sort(numbers);
        return numbers;
    }

    private static String batchFile(long seq, boolean snapshot) {
        return (snapshot ? "snapshot-" : "") + String.format("%020d", seq) + ".jmcrm";
    }

    /** The name a file is sealed under: where it belongs in the folder. */
    private static String name(int node, String file) {
        return "computer-" + node + "/" + file;
    }

    private void write(Path target, String name, Object value) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(packed)) {
            json.writeValue(gzip, value);
        }
        writeSealed(target, name, packed.toByteArray());
    }

    /** Seals and writes under a temporary name, then renames, so a half-written file is never read. */
    private void writeSealed(Path target, String name, byte[] content) throws IOException {
        byte[] sealed = cipher.seal(name, content);
        Path temporary = target.resolveSibling(".writing-" + UUID.randomUUID() + ".tmp");
        Files.write(temporary, sealed);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notOnThisDrive) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private <T> T read(Path file, String name, Class<T> type) throws IOException, UnreadableException {
        byte[] stored;
        try {
            stored = Files.readAllBytes(file);
        } catch (NoSuchFileException gone) {
            throw new UnreadableException(name + " is gone");
        }
        byte[] packed = cipher.open(name, stored);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return json.readValue(in, type);
        } catch (IOException damaged) {
            throw new UnreadableException(name + " couldn't be read: " + damaged.getMessage());
        }
    }
}
