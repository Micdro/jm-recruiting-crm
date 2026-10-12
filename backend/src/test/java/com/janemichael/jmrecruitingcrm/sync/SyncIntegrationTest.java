package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.TestSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync against a real PostgreSQL database. This computer is computer 1; changes "from computer 2"
 * are built by hand. Each test rolls back.
 */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, TestSettings.NO_SYNC_TIMER})
@Transactional
class SyncIntegrationTest {

    private static final long REMOTE = 2 * SyncTables.RANGE;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SyncIdentity identity;

    @Autowired
    private SyncApplier applier;

    @Autowired
    private SyncFeed feed;

    private long now;

    @BeforeEach
    void becomeComputerOne() {
        identity.become(1, "Test computer");
        now = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());
    }

    @Test
    void newRecordsUseThisComputersIdsAndAreTracked() {
        long id = insertCompany("Local Co");

        assertThat(id).isGreaterThan(SyncTables.RANGE).isLessThan(2 * SyncTables.RANGE);
        Map<String, Object> meta = meta("company", id);
        assertThat(meta.get("origin")).isEqualTo(1);
        assertThat(meta.get("deleted")).isEqualTo(false);
    }

    @Test
    void changesFromAnotherComputerApplyParentsFirstAndKeepTheirVersion() {
        SyncChange contact = remote("contact", REMOTE + 2, now, Map.of(
                "name", "Remote Person", "company_id", String.valueOf(REMOTE + 1), "status", "NEW", "touch_count", "2"));
        SyncChange company = remote("company", REMOTE + 1, now, Map.of("name", "Remote Co", "status", "LEAD"));

        SyncApplier.Result result = applier.apply(List.of(contact, company));

        assertThat(result.applied()).isEqualTo(2);
        assertThat(result.waiting()).isZero();
        assertThat(jdbc.queryForObject("SELECT touch_count FROM contact WHERE id = ?", Integer.class, REMOTE + 2)).isEqualTo(2);
        assertThat(meta("company", REMOTE + 1)).containsEntry("origin", 2).containsEntry("version", now);
    }

    @Test
    void olderVersionsAreIgnoredAndNewerOnesWin() {
        applier.apply(List.of(remote("company", REMOTE + 1, now, Map.of("name", "Second name", "status", "LEAD"))));

        SyncApplier.Result older = applier.apply(List.of(
                remote("company", REMOTE + 1, now - 1_000_000, Map.of("name", "First name", "status", "LEAD"))));
        assertThat(older.skipped()).isEqualTo(1);
        assertThat(companyName(REMOTE + 1)).isEqualTo("Second name");

        applier.apply(List.of(remote("company", REMOTE + 1, now + 1_000_000, Map.of("name", "Third name", "status", "ENGAGED"))));
        assertThat(companyName(REMOTE + 1)).isEqualTo("Third name");
    }

    @Test
    void aRowWaitsForItsParentThenApplies() {
        SyncApplier.Result first = applier.apply(List.of(remote("contact", REMOTE + 2, now, Map.of(
                "name", "Early Arrival", "company_id", String.valueOf(REMOTE + 1), "status", "NEW"))));
        assertThat(first.waiting()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_pending", Integer.class)).isEqualTo(1);

        SyncApplier.Result second = applier.apply(List.of(
                remote("company", REMOTE + 1, now, Map.of("name", "Late Co", "status", "LEAD"))));
        assertThat(second.applied()).isEqualTo(2);
        assertThat(second.waiting()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact WHERE id = ?", Integer.class, REMOTE + 2)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_pending", Integer.class)).isZero();
    }

    @Test
    void aCompanyDeletedElsewhereThatStillHasContactsHereIsSentBack() {
        long companyId = insertCompany("Still Needed Co");
        insertContact(companyId, "Kept Contact");
        long deletedAt = now + 10_000_000;

        applier.apply(List.of(deleted("company", companyId, deletedAt)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM company WHERE id = ?", Integer.class, companyId)).isEqualTo(1);
        Map<String, Object> meta = meta("company", companyId);
        assertThat(meta.get("deleted")).isEqualTo(false);
        assertThat(meta.get("origin")).isEqualTo(1);
        assertThat((Long) meta.get("version")).isGreaterThan(deletedAt);
    }

    @Test
    void deletesFromAnotherComputerRemoveTheRowAndItsNotes() {
        long companyId = insertCompany("Gone Co");
        long contactId = insertContact(companyId, "Gone Contact");

        applier.apply(List.of(deleted("contact", contactId, now + 1_000_000)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact WHERE id = ?", Integer.class, contactId)).isZero();
        assertThat(meta("contact", contactId)).containsEntry("deleted", true).containsEntry("origin", 2);

        // A note written on the other computer before it heard about the delete has nowhere to go.
        SyncApplier.Result late = applier.apply(List.of(remote("activity", REMOTE + 9, now + 2_000_000, Map.of(
                "type", "NOTE", "touch", "false", "occurred_at", "2026-10-11 12:00:00+00",
                "contact_id", String.valueOf(contactId)))));
        assertThat(late.dropped()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activity WHERE id = ?", Integer.class, REMOTE + 9)).isZero();
    }

    @Test
    void theSameCandidateAddedToASearchOnTwoComputersKeepsTheLowerId() {
        long companyId = insertCompany("Search Co");
        long searchId = jdbc.queryForObject("""
                INSERT INTO job_search (company_id, title, track, status, engagement_type)
                VALUES (?, 'FDE', 'PRODUCTION_AI_ENGINEERING', 'ACTIVE', 'EXCLUSIVE') RETURNING id
                """, Long.class, companyId);
        long candidateId = jdbc.queryForObject(
                "INSERT INTO candidate (name, status) VALUES ('Avery', 'ACTIVE') RETURNING id", Long.class);
        long localEntry = jdbc.queryForObject("""
                INSERT INTO pipeline_entry (search_id, candidate_id, stage) VALUES (?, ?, 'SOURCED') RETURNING id
                """, Long.class, searchId, candidateId);
        Map<String, String> entry = Map.of(
                "search_id", String.valueOf(searchId), "candidate_id", String.valueOf(candidateId), "stage", "SHORTLISTED");

        SyncApplier.Result higher = applier.apply(List.of(remote("pipeline_entry", REMOTE + 5, now, entry)));
        assertThat(higher.dropped()).isEqualTo(1);
        assertThat(pipelineIds(searchId)).containsExactly(localEntry);
        assertThat(meta("pipeline_entry", REMOTE + 5)).containsEntry("deleted", true).containsEntry("origin", 1);

        SyncApplier.Result lower = applier.apply(List.of(remote("pipeline_entry", 7, now, entry)));
        assertThat(lower.applied()).isEqualTo(1);
        assertThat(pipelineIds(searchId)).containsExactly(7L);
    }

    @Test
    void theFeedSendsChangedRowsAsTextAndDeletedRowsWithoutData() {
        long keep = insertCompany("Feed Co");
        long gone = insertCompany("Feed Gone Co");
        jdbc.update("DELETE FROM company WHERE id = ?", gone);

        SyncFeed.Feed changes = feed.since(0);

        SyncChange kept = find(changes.changes(), "company", keep);
        assertThat(kept.deleted()).isFalse();
        assertThat(kept.origin()).isEqualTo(1);
        assertThat(kept.row()).containsEntry("name", "Feed Co").containsEntry("status", "LEAD");
        SyncChange removed = find(changes.changes(), "company", gone);
        assertThat(removed.deleted()).isTrue();
        assertThat(removed.row()).isNull();
        assertThat(find(changes.changes(), "sync_node", 1).row()).containsEntry("name", "Test computer");
    }

    @Test
    void addressesAreNormalized() {
        assertThat(SyncClient.normalizeAddress(" michael-pc ", 4781)).isEqualTo("michael-pc:4781");
        assertThat(SyncClient.normalizeAddress("http://100.101.102.103/", 4781)).isEqualTo("100.101.102.103:4781");
        assertThat(SyncClient.normalizeAddress("michael-pc:5000", 4781)).isEqualTo("michael-pc:5000");
        assertThat(SyncClient.normalizeAddress("  ", 4781)).isNull();
    }

    // Helpers -----------------------------------------------------------------

    private SyncChange remote(String table, long id, long version, Map<String, String> values) {
        Map<String, String> row = new HashMap<>(values);
        row.put("id", String.valueOf(id));
        return new SyncChange(table, id, version, 2, false, row);
    }

    private SyncChange deleted(String table, long id, long version) {
        return new SyncChange(table, id, version, 2, true, null);
    }

    private long insertCompany(String name) {
        return jdbc.queryForObject("INSERT INTO company (name, status) VALUES (?, 'LEAD') RETURNING id", Long.class, name);
    }

    private long insertContact(long companyId, String name) {
        return jdbc.queryForObject("INSERT INTO contact (name, company_id) VALUES (?, ?) RETURNING id",
                Long.class, name, companyId);
    }

    private String companyName(long id) {
        return jdbc.queryForObject("SELECT name FROM company WHERE id = ?", String.class, id);
    }

    private List<Long> pipelineIds(long searchId) {
        return jdbc.queryForList("SELECT id FROM pipeline_entry WHERE search_id = ? ORDER BY id", Long.class, searchId);
    }

    private Map<String, Object> meta(String table, long id) {
        return jdbc.queryForMap("""
                SELECT (extract(epoch FROM version) * 1000000)::bigint AS version, origin, deleted
                FROM sync_row WHERE table_name = ? AND row_id = ?
                """, table, id);
    }

    private static SyncChange find(List<SyncChange> changes, String table, long id) {
        return changes.stream()
                .filter(change -> change.table().equals(table) && change.id() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No change for " + table + " " + id));
    }
}
