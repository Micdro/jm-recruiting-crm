package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.TestSettings;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.sync.SyncFolder.About;
import com.janemichael.jmrecruitingcrm.sync.SyncFolder.Batch;
import com.janemichael.jmrecruitingcrm.sync.SyncFolder.Belongs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Syncing through a shared folder, against a real database. This computer is computer 1; the files
 * "from computer 2" (and 3) are written by hand, as another computer's copy would write them.
 * Each test rolls back, and gets its own empty folder.
 */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, TestSettings.NO_SYNC_TIMER})
@Transactional
class FolderSyncIntegrationTest {

    private static final long REMOTE = 2 * SyncTables.RANGE;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SyncIdentity identity;

    @Autowired
    private SyncFeed feed;

    @Autowired
    private SyncApplier applier;

    @Autowired
    private FieldEncryptor encryptor;

    @Autowired
    private FolderSync folderSync;

    @TempDir
    Path shared;

    /** The folder as the other computers see it. */
    private SyncFolder folder;
    private long now;

    @BeforeEach
    void becomeComputerOne() {
        identity.become(1, "Test computer");
        folder = new SyncFolder(shared, encryptor.syncToken());
        now = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());
    }

    @Test
    void thisComputersChangesAreWrittenEncryptedInItsOwnFolder() throws Exception {
        long id = jdbc.queryForObject(
                "INSERT INTO company (name, status) VALUES ('Folder Test Co', 'LEAD') RETURNING id", Long.class);

        folderSync.useFolder(shared.toString());

        assertThat(folder.belongs()).isEqualTo(Belongs.THIS_CRM);
        assertThat(shared.resolve(SyncFolder.README)).exists();
        About about = folder.readAbout(1);
        assertThat(about).isNotNull();
        List<Long> batches = folder.batches(1);
        assertThat(batches).isNotEmpty();
        assertThat(about.lastBatch()).isEqualTo(batches.get(batches.size() - 1));

        List<SyncChange> written = new ArrayList<>();
        for (long seq : batches) {
            written.addAll(folder.readBatch(1, seq, false).changes());
        }
        assertThat(written).anySatisfy(change -> {
            assertThat(change.key()).isEqualTo("company:" + id);
            assertThat(change.row()).containsEntry("name", "Folder Test Co");
        });
        // Even a value stored in plain text is unreadable in the folder.
        assertThat(filesContaining("Folder Test Co")).isEmpty();

        // Nothing changed, so nothing is written again.
        folderSync.syncNow();
        assertThat(folder.batches(1)).isEqualTo(batches);
        assertThat(folderSync.status().problem()).isNull();
    }

    @Test
    void anotherComputersFilesApplyInOrderAndAMissingFileIsWaitedFor() throws Exception {
        folderSync.useFolder(shared.toString());
        writeAbout(2, "first-set", 3);
        folder.writeBatch(batch(2, 1, company(REMOTE + 1, "First Remote Co")));
        folder.writeBatch(batch(2, 3, company(REMOTE + 3, "Third Remote Co")));

        folderSync.syncNow();

        assertThat(companyName(REMOTE + 1)).isEqualTo("First Remote Co");
        assertThat(companyExists(REMOTE + 3)).as("waits for file 2 to arrive").isFalse();
        assertThat(peerError(2)).contains("Waiting for Google Drive");

        folder.writeBatch(batch(2, 2, company(REMOTE + 2, "Second Remote Co")));
        folderSync.syncNow();

        assertThat(companyName(REMOTE + 2)).isEqualTo("Second Remote Co");
        assertThat(companyName(REMOTE + 3)).isEqualTo("Third Remote Co");
        assertThat(peerError(2)).isNull();
    }

    @Test
    void aComputerThatFellBehindReadsTheSnapshotThatReplacedOlderFiles() throws Exception {
        folderSync.useFolder(shared.toString());
        writeAbout(2, "compacted-set", 6);
        folder.writeBatch(snapshot(2, 5, company(REMOTE + 5, "Snapshot Co")));
        folder.writeBatch(batch(2, 6, company(REMOTE + 6, "After Snapshot Co")));

        folderSync.syncNow();

        assertThat(companyName(REMOTE + 5)).isEqualTo("Snapshot Co");
        assertThat(companyName(REMOTE + 6)).isEqualTo("After Snapshot Co");
        assertThat(peerError(2)).isNull();
    }

    @Test
    void aNewSetOfFilesFromAComputerIsReadFromTheStart() throws Exception {
        folderSync.useFolder(shared.toString());
        writeAbout(2, "old-set", 1);
        folder.writeBatch(batch(2, 1, company(REMOTE + 1, "Old Set Co")));
        folderSync.syncNow();

        // That computer started over in an emptied folder: its numbers begin at 1 again.
        Files.delete(folder.computerDir(2).resolve(String.format("%020d.jmcrm", 1)));
        writeAbout(2, "new-set", 1);
        folder.writeBatch(batch(2, 1, company(REMOTE + 7, "New Set Co")));
        folderSync.syncNow();

        assertThat(companyName(REMOTE + 7)).isEqualTo("New Set Co");
    }

    @Test
    void manyFilesAreFoldedIntoOneSnapshot() throws Exception {
        FolderSync compacting = new FolderSync(jdbc, identity, feed, applier, encryptor, 2);
        compacting.useFolder(shared.toString());
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO company (name, status) VALUES (?, 'LEAD')", "Compact Co " + i);
            compacting.syncNow();
        }

        // Files 1 to 3 became one snapshot; file 4 came after it.
        assertThat(folder.snapshots(1)).containsExactly(3L);
        assertThat(folder.batches(1)).containsExactly(4L);
        List<SyncChange> everything = folder.readBatch(1, 3, true).changes();
        assertThat(everything).anySatisfy(change -> assertThat(change.row()).containsEntry("name", "Compact Co 0"));
        assertThat(everything).anySatisfy(change -> assertThat(change.row()).containsEntry("name", "Compact Co 1"));
        assertThat(folder.readAbout(1).lastBatch()).isEqualTo(4L);
    }

    @Test
    void aJoiningComputerCopiesEveryComputersFilesAndTakesTheNextNumber() throws Exception {
        folder.claim();
        writeAbout(2, "two", 1);
        folder.writeBatch(batch(2, 1, company(REMOTE + 1, "From Two Co")));
        writeAbout(3, "three", 1);
        folder.writeBatch(batch(3, 1, company(3 * SyncTables.RANGE + 1, "From Three Co")));

        FolderSync.Copy copy = folderSync.copyEverything(shared.toString(), encryptor.syncToken());

        assertThat(copy.computers()).isEqualTo(2);
        assertThat(copy.applied()).isEqualTo(2);
        assertThat(copy.warning()).isNull();
        assertThat(companyName(REMOTE + 1)).isEqualTo("From Two Co");
        assertThat(companyName(3 * SyncTables.RANGE + 1)).isEqualTo("From Three Co");
        assertThat(folderSync.nextNodeId(shared.toString())).isEqualTo(4);
    }

    @Test
    void aFolderThatIsEmptyOrHoldsAnotherCrmIsRefusedForJoining() throws Exception {
        assertThatThrownBy(() -> folderSync.copyEverything(shared.toString(), encryptor.syncToken()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no CRM in that folder");

        new SyncFolder(shared, "another-crms-sync-token").claim();

        assertThatThrownBy(() -> folderSync.copyEverything(shared.toString(), encryptor.syncToken()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("different key");
        assertThatThrownBy(() -> folderSync.useFolder(shared.toString()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("different key");
        assertThatThrownBy(() -> folderSync.checkFreeForNewCrm(shared.toString()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already holds a CRM");
    }

    @Test
    void pastedPathsAreCleanedAndANewFolderIsCreatedInsideAnExistingOne() {
        assertThat(FolderSync.prepare("  \"" + shared + "\"  ")).isEqualTo(shared);
        assertThat(FolderSync.prepare(shared.resolve("JM CRM Sync").toString())).isDirectory();
        assertThatThrownBy(() -> FolderSync.prepare(shared.resolve("missing").resolve("JM CRM Sync").toString()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Can't find");
        assertThatThrownBy(() -> FolderSync.prepare(" "))
                .isInstanceOf(BadRequestException.class);
    }

    // Helpers -----------------------------------------------------------------

    private void writeAbout(int node, String instance, long lastBatch) throws IOException {
        folder.writeAbout(new About(SyncFolder.PROTOCOL, node, instance, "Computer " + node, lastBatch,
                System.currentTimeMillis()));
    }

    private static Batch batch(int node, long seq, SyncChange... changes) {
        return new Batch(SyncFolder.PROTOCOL, node, seq, false, List.of(changes));
    }

    private static Batch snapshot(int node, long seq, SyncChange... changes) {
        return new Batch(SyncFolder.PROTOCOL, node, seq, true, List.of(changes));
    }

    private SyncChange company(long id, String name) {
        Map<String, String> row = new HashMap<>();
        row.put("id", String.valueOf(id));
        row.put("name", name);
        row.put("status", "LEAD");
        return new SyncChange("company", id, now, (int) (id / SyncTables.RANGE), false, row);
    }

    private String companyName(long id) {
        return jdbc.queryForObject("SELECT name FROM company WHERE id = ?", String.class, id);
    }

    private boolean companyExists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM company WHERE id = ?)", Boolean.class, id));
    }

    private String peerError(int node) {
        return jdbc.queryForObject("SELECT last_error FROM sync_peer_state WHERE node_id = ?", String.class, node);
    }

    private List<Path> filesContaining(String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(shared)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (indexOf(Files.readAllBytes(file), needle) >= 0) {
                    found.add(file);
                }
            }
        }
        return found;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
