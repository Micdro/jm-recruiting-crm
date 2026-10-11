package com.janemichael.jmrecruitingcrm.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What this computer has to offer the others: every shared row written since a cursor.
 *
 * <p>The cursor is a PostgreSQL transaction id. Each answer comes from one consistent snapshot and
 * returns the oldest transaction still running as the next cursor, so a change made by a long
 * transaction (an import, say) is never skipped: it is sent once that transaction commits. Some
 * rows may be sent twice; applying a version that is already there does nothing.
 */
@Component
public class SyncFeed {

    private static final int ROWS_PER_QUERY = 500;

    public record Feed(long cursor, List<SyncChange> changes) {
    }

    private record Entry(String table, long id, long version, int origin, boolean deleted) {
    }

    private final JdbcTemplate jdbc;
    private final SyncCatalog catalog;
    private final TransactionTemplate snapshot;

    public SyncFeed(JdbcTemplate jdbc, SyncCatalog catalog, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    public Feed since(long cursor) {
        return snapshot.execute(status -> {
            Long next = jdbc.queryForObject(
                    "SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint", Long.class);

            List<Entry> entries = jdbc.query("""
                    SELECT table_name, row_id, (extract(epoch FROM version) * 1000000)::bigint AS version,
                           origin, deleted
                    FROM sync_row
                    WHERE txid >= ?
                    ORDER BY txid, table_name, row_id
                    """, (resultSet, row) -> new Entry(
                    resultSet.getString("table_name"),
                    resultSet.getLong("row_id"),
                    resultSet.getLong("version"),
                    resultSet.getInt("origin"),
                    resultSet.getBoolean("deleted")), cursor);

            Map<String, List<Long>> liveIds = new LinkedHashMap<>();
            for (Entry entry : entries) {
                if (!entry.deleted()) {
                    liveIds.computeIfAbsent(entry.table(), key -> new ArrayList<>()).add(entry.id());
                }
            }
            Map<String, Map<Long, Map<String, String>>> rows = new HashMap<>();
            liveIds.forEach((table, ids) -> rows.put(table, readRows(table, ids)));

            List<SyncChange> changes = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                Map<String, String> row = entry.deleted()
                        ? null
                        : rows.getOrDefault(entry.table(), Map.of()).get(entry.id());
                // A row marked live but missing was deleted by a change that hasn't committed yet;
                // its deletion is sent next time.
                if (!entry.deleted() && row == null) {
                    continue;
                }
                changes.add(new SyncChange(entry.table(), entry.id(), entry.version(), entry.origin(),
                        entry.deleted(), row));
            }
            return new Feed(next == null ? cursor : next, changes);
        });
    }

    /** Reads rows with every column as text, exactly as PostgreSQL prints it. */
    Map<Long, Map<String, String>> readRows(String table, List<Long> ids) {
        Map<String, String> columns = catalog.columns(table);
        String select = columns.keySet().stream()
                .map(column -> column + "::text AS " + column)
                .collect(Collectors.joining(", "));

        Map<Long, Map<String, String>> rows = new HashMap<>();
        for (int start = 0; start < ids.size(); start += ROWS_PER_QUERY) {
            String idList = ids.subList(start, Math.min(ids.size(), start + ROWS_PER_QUERY)).stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            jdbc.query("SELECT " + select + " FROM " + table + " WHERE id IN (" + idList + ")", resultSet -> {
                Map<String, String> row = new LinkedHashMap<>();
                for (String column : columns.keySet()) {
                    row.put(column, resultSet.getString(column));
                }
                rows.put(Long.parseLong(row.get("id")), row);
            });
        }
        return rows;
    }
}
