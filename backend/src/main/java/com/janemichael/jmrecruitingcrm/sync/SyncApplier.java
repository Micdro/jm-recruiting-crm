package com.janemichael.jmrecruitingcrm.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.sync.SyncCatalog.ForeignKey;
import com.janemichael.jmrecruitingcrm.sync.SyncCatalog.OnDelete;
import com.janemichael.jmrecruitingcrm.sync.SyncTables.NaturalKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Copies changes from another computer into this one.
 *
 * <ul>
 *   <li>The newest version of a row wins. Older or identical versions are ignored, so a change can
 *       arrive twice, or by way of a third computer, without harm.</li>
 *   <li>A row whose parent hasn't arrived yet (a contact before its company) waits in sync_pending
 *       and is retried after every sync.</li>
 *   <li>Deleting a parent that another computer has just given a child (a company that just got a
 *       contact) doesn't happen: the parent is sent back to everyone, child and all.</li>
 *   <li>A child of a deleted parent that would have been removed with it (a note on a deleted
 *       contact) is dropped.</li>
 *   <li>If two computers created the same teammate, or put the same candidate in the same search,
 *       the copy with the lower id is kept everywhere.</li>
 * </ul>
 */
@Component
public class SyncApplier {

    private static final Logger log = LoggerFactory.getLogger(SyncApplier.class);

    public record Result(int applied, int skipped, int waiting, int dropped) {
    }

    private enum Outcome { APPLIED, SKIPPED, WAITING, DROPPED }

    private record Meta(long version, int origin, boolean deleted) {
    }

    private final JdbcTemplate jdbc;
    private final SyncCatalog catalog;
    private final SyncIdentity identity;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();

    public SyncApplier(
            JdbcTemplate jdbc,
            SyncCatalog catalog,
            SyncIdentity identity,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.identity = identity;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Applies a batch from another computer, together with anything still waiting from earlier. */
    public synchronized Result apply(List<SyncChange> incoming) {
        return transaction.execute(status -> applyAll(incoming));
    }

    private Result applyAll(List<SyncChange> incoming) {
        Map<String, SyncChange> changes = new LinkedHashMap<>();
        for (SyncChange waiting : loadWaiting()) {
            changes.put(waiting.key(), waiting);
        }
        for (SyncChange change : incoming) {
            if (!SyncTables.isShared(change.table())) {
                continue;
            }
            SyncChange earlier = changes.get(change.key());
            if (earlier == null || change.isNewerThan(earlier.version(), earlier.origin())) {
                changes.put(change.key(), change);
            }
        }

        Comparator<SyncChange> parentsFirst = Comparator
                .comparingInt((SyncChange change) -> SyncTables.position(change.table()))
                .thenComparingLong(SyncChange::id);

        List<SyncChange> upserts = changes.values().stream().filter(change -> !change.deleted())
                .sorted(parentsFirst).collect(Collectors.toCollection(ArrayList::new));
        List<SyncChange> deletes = changes.values().stream().filter(SyncChange::deleted)
                .sorted(parentsFirst.reversed()).toList();

        int applied = 0;
        int skipped = 0;
        int dropped = 0;
        Map<String, String> waiting = new LinkedHashMap<>();
        Map<String, SyncChange> waitingChanges = new LinkedHashMap<>();

        // Rows whose parent comes later in the same batch succeed on a later pass.
        boolean progress = true;
        while (progress && !upserts.isEmpty()) {
            progress = false;
            waiting.clear();
            waitingChanges.clear();
            List<SyncChange> stillWaiting = new ArrayList<>();
            for (SyncChange change : upserts) {
                String[] reason = new String[1];
                Outcome outcome = safely(change, () -> applyUpsert(change, reason), reason);
                switch (outcome) {
                    case APPLIED -> {
                        applied++;
                        progress = true;
                    }
                    case SKIPPED -> {
                        skipped++;
                        progress = true;
                    }
                    case DROPPED -> {
                        dropped++;
                        progress = true;
                    }
                    case WAITING -> {
                        stillWaiting.add(change);
                        waiting.put(change.key(), reason[0]);
                        waitingChanges.put(change.key(), change);
                    }
                }
            }
            upserts = stillWaiting;
        }

        for (SyncChange change : deletes) {
            String[] reason = new String[1];
            Outcome outcome = safely(change, () -> applyDelete(change), reason);
            switch (outcome) {
                case APPLIED -> applied++;
                case SKIPPED -> skipped++;
                case DROPPED -> dropped++;
                case WAITING -> {
                    waiting.put(change.key(), reason[0]);
                    waitingChanges.put(change.key(), change);
                }
            }
        }

        saveWaiting(waitingChanges, waiting);
        if (!waiting.isEmpty()) {
            log.info("{} synced changes are waiting for related records", waiting.size());
        }
        return new Result(applied, skipped, waiting.size(), dropped);
    }

    private interface Step {
        Outcome run();
    }

    /** Runs one row in a savepoint, so a problem with it doesn't undo the rest of the batch. */
    private Outcome safely(SyncChange change, Step step, String[] reason) {
        jdbc.execute("SAVEPOINT sync_change");
        try {
            Outcome outcome = step.run();
            jdbc.execute("RELEASE SAVEPOINT sync_change");
            return outcome;
        } catch (DataAccessException exception) {
            jdbc.execute("ROLLBACK TO SAVEPOINT sync_change");
            Throwable cause = exception.getMostSpecificCause();
            reason[0] = truncate(cause == null ? exception.getMessage() : cause.getMessage());
            log.warn("Could not apply synced change {}: {}", change.key(), reason[0]);
            return Outcome.WAITING;
        } catch (IllegalArgumentException exception) {
            jdbc.execute("ROLLBACK TO SAVEPOINT sync_change");
            reason[0] = truncate(exception.getMessage());
            log.warn("Could not apply synced change {}: {}", change.key(), reason[0]);
            return Outcome.WAITING;
        }
    }

    private Outcome applyUpsert(SyncChange change, String[] reason) {
        String table = change.table();
        Meta local = meta(table, change.id());
        if (local != null && !change.isNewerThan(local.version(), local.origin())) {
            return Outcome.SKIPPED;
        }
        if (change.row() == null) {
            throw new IllegalArgumentException("The change has no data");
        }

        Map<String, String> row = new LinkedHashMap<>(change.row());

        for (ForeignKey key : catalog.parentsOf(table)) {
            String value = row.get(key.column());
            if (value == null || exists(key.parent(), Long.parseLong(value))) {
                continue;
            }
            Meta parent = meta(key.parent(), Long.parseLong(value));
            if (parent != null && parent.deleted()) {
                if (key.onDelete() == OnDelete.CASCADE) {
                    return Outcome.DROPPED;
                }
                if (key.onDelete() == OnDelete.SET_NULL) {
                    row.put(key.column(), null);
                    continue;
                }
                reason[0] = "Waiting for a deleted " + key.parent() + " (" + value + ") to be restored";
                return Outcome.WAITING;
            }
            reason[0] = "Waiting for " + key.parent() + " " + value;
            return Outcome.WAITING;
        }

        NaturalKey naturalKey = SyncTables.NATURAL_KEYS.get(table);
        if (naturalKey != null) {
            List<Object> params = new ArrayList<>();
            for (String column : naturalKey.columns()) {
                params.add(row.get(column));
            }
            if (params.stream().allMatch(value -> value != null)) {
                params.add(change.id());
                List<Long> others = jdbc.queryForList(
                        "SELECT id FROM " + table + " WHERE " + naturalKey.where() + " AND id <> ?",
                        Long.class, params.toArray());
                for (Long other : others) {
                    if (change.id() < other) {
                        jdbc.update("DELETE FROM " + table + " WHERE id = ?", other);
                    } else {
                        // Keep ours; tell the computer that made this one to remove it.
                        writeMeta(table, change.id(), change.version(), identity.nodeId(), true, true);
                        return Outcome.DROPPED;
                    }
                }
            }
        }

        Map<String, String> types = catalog.columns(table);
        List<String> columns = row.keySet().stream().filter(types::containsKey).toList();
        if (!columns.contains("id")) {
            throw new IllegalArgumentException("The change has no id");
        }
        String insert = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + columns.stream().map(column -> "?::" + types.get(column)).collect(Collectors.joining(", "))
                + ") ON CONFLICT (id) DO UPDATE SET "
                + columns.stream().filter(column -> !column.equals("id"))
                        .map(column -> column + " = EXCLUDED." + column).collect(Collectors.joining(", "));

        applying(change.key());
        jdbc.update(insert, columns.stream().map(row::get).toArray());
        applying("");
        writeMeta(table, change.id(), change.version(), change.origin(), false, false);
        return Outcome.APPLIED;
    }

    private Outcome applyDelete(SyncChange change) {
        String table = change.table();
        Meta local = meta(table, change.id());
        if (local != null && !change.isNewerThan(local.version(), local.origin())) {
            return Outcome.SKIPPED;
        }

        if (exists(table, change.id())) {
            for (ForeignKey key : catalog.childrenOf(table)) {
                if (key.onDelete() == OnDelete.RESTRICT && hasChild(key, change.id())) {
                    // Someone here still relies on it. Send it back to everyone instead.
                    writeMeta(table, change.id(), change.version(), identity.nodeId(), false, true);
                    return Outcome.APPLIED;
                }
            }
            applying(change.key());
            jdbc.update("DELETE FROM " + table + " WHERE id = ?", change.id());
            applying("");
        }
        writeMeta(table, change.id(), change.version(), change.origin(), true, false);
        return Outcome.APPLIED;
    }

    private boolean hasChild(ForeignKey key, long parentId) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + key.child() + " WHERE " + key.column() + " = ?)",
                Boolean.class, parentId);
        return Boolean.TRUE.equals(found);
    }

    private boolean exists(String table, long id) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(found);
    }

    private Meta meta(String table, long id) {
        List<Meta> rows = jdbc.query("""
                SELECT (extract(epoch FROM version) * 1000000)::bigint AS version, origin, deleted
                FROM sync_row WHERE table_name = ? AND row_id = ?
                """, (resultSet, row) -> new Meta(
                resultSet.getLong("version"), resultSet.getInt("origin"), resultSet.getBoolean("deleted")),
                table, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Records a row's version. With {@code supersede}, the version is made newer than both the given
     * one and the current time, so this computer's decision wins everywhere.
     */
    private void writeMeta(String table, long id, long version, int origin, boolean deleted, boolean supersede) {
        String versionSql = supersede
                ? "greatest(clock_timestamp(), timestamptz 'epoch' + (?::float8 + 1) * interval '1 microsecond')"
                : "timestamptz 'epoch' + ?::float8 * interval '1 microsecond'";
        jdbc.update("""
                INSERT INTO sync_row (table_name, row_id, version, origin, deleted, txid)
                VALUES (?, ?, %s, ?, ?, pg_current_xact_id()::text::bigint)
                ON CONFLICT (table_name, row_id) DO UPDATE
                    SET version = EXCLUDED.version, origin = EXCLUDED.origin,
                        deleted = EXCLUDED.deleted, txid = EXCLUDED.txid
                """.formatted(versionSql), table, id, version, origin, deleted);
    }

    /** Marks the row being copied in, so the change-tracking trigger leaves its version alone. */
    private void applying(String key) {
        jdbc.queryForObject("SELECT set_config('jmcrm.applying_row', ?, true)", String.class, key);
    }

    private List<SyncChange> loadWaiting() {
        List<String> stored = jdbc.queryForList("SELECT change FROM sync_pending", String.class);
        List<SyncChange> changes = new ArrayList<>();
        for (String value : stored) {
            try {
                changes.add(json.readValue(value, SyncChange.class));
            } catch (JsonProcessingException exception) {
                log.warn("Dropping an unreadable waiting change: {}", exception.getMessage());
            }
        }
        return changes;
    }

    private void saveWaiting(Map<String, SyncChange> changes, Map<String, String> reasons) {
        Set<String> keep = new HashSet<>(changes.keySet());
        jdbc.update("DELETE FROM sync_pending");
        for (String key : keep) {
            SyncChange change = changes.get(key);
            try {
                jdbc.update("INSERT INTO sync_pending (table_name, row_id, change, reason) VALUES (?, ?, ?, ?)",
                        change.table(), change.id(), json.writeValueAsString(change), reasons.get(key));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Could not store a waiting change", exception);
            }
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "Unknown problem";
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
