package com.janemichael.jmrecruitingcrm.trash;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.sync.SyncCatalog;
import com.janemichael.jmrecruitingcrm.sync.SyncCatalog.ForeignKey;
import com.janemichael.jmrecruitingcrm.sync.SyncCatalog.OnDelete;
import com.janemichael.jmrecruitingcrm.sync.SyncTables;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps what a delete removes, so it can be put back. Before a company, contact, candidate, search,
 * placement or file is deleted, the record and everything deleted along with it (a contact's
 * history, a candidate's pipeline and files) is saved in one trash item, encrypted. Links that the
 * delete clears (a search's hiring manager) are noted too, and set again on restore.
 *
 * <p>Trash items sync like any other record, so a delete on one computer can be undone on another.
 * Restoring inserts the rows again with their own ids; sync sends them on as new changes. Items
 * are removed for good after {@link #KEEP_DAYS} days.
 */
@Service
public class TrashService {

    public static final int KEEP_DAYS = 30;

    /** Everything one delete removed. Column values are as the database stores them (encrypted ones stay encrypted). */
    record Snapshot(List<Row> rows, List<Unlink> unlinks) {
    }

    record Row(String table, long id, Map<String, String> row) {
    }

    /** A link the delete cleared: {@code table.column} of row {@code id} pointed at {@code parent}. */
    record Unlink(String table, long id, String column, long parent) {
    }

    public record TrashItemResponse(
            Long id,
            TrashKind kind,
            Long recordId,
            String label,
            String detail,
            String path,
            String deletedByName,
            Instant deletedAt,
            Instant removedForGoodAt
    ) {
    }

    public record Restored(TrashKind kind, Long recordId, String label, String path) {
    }

    private record Description(String label, String detail, String path) {
    }

    private final JdbcTemplate jdbc;
    private final SyncCatalog catalog;
    private final FieldEncryptor encryptor;
    private final CurrentUserService currentUser;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TrashService(JdbcTemplate jdbc, SyncCatalog catalog, FieldEncryptor encryptor, CurrentUserService currentUser) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.encryptor = encryptor;
        this.currentUser = currentUser;
    }

    /**
     * Saves the record and everything its delete will remove. Call it in the same transaction as the
     * delete, just before it. Does nothing if the record isn't there (the delete then reports it).
     */
    @Transactional
    public void capture(TrashKind kind, long id) {
        Map<String, String> root = readRows(kind.table, List.of(id)).get(id);
        if (root == null) {
            return;
        }
        Snapshot snapshot = new Snapshot(new ArrayList<>(), new ArrayList<>());
        collect(kind.table, List.of(id), snapshot, new HashSet<>());

        Description description = describe(kind, id, root, snapshot);
        Set<String> blobKeys = snapshot.rows().stream()
                .filter(row -> row.table().equals("candidate_file"))
                .map(row -> row.row().get("blob_key"))
                .filter(key -> key != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String contents;
        try {
            contents = json.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not save the deleted record", exception);
        }
        AppUser user = currentUser.get();
        jdbc.update("""
                INSERT INTO trash_item (kind, record_id, label, detail, contents, blob_keys, path, deleted_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, kind.name(), id, encryptor.encrypt(description.label()), encryptor.encrypt(description.detail()),
                encryptor.encrypt(contents), blobKeys.isEmpty() ? null : String.join(" ", blobKeys),
                description.path(), user == null ? null : user.getId());
    }

    @Transactional(readOnly = true)
    public List<TrashItemResponse> list() {
        return jdbc.query("""
                SELECT t.id, t.kind, t.record_id, t.label, t.detail, t.path, t.deleted_at, u.display_name
                FROM trash_item t LEFT JOIN app_user u ON u.id = t.deleted_by_id
                ORDER BY t.deleted_at DESC, t.id DESC
                """, (resultSet, row) -> {
            Instant deletedAt = resultSet.getTimestamp("deleted_at").toInstant();
            return new TrashItemResponse(
                    resultSet.getLong("id"),
                    TrashKind.valueOf(resultSet.getString("kind")),
                    resultSet.getLong("record_id"),
                    encryptor.decrypt(resultSet.getString("label")),
                    encryptor.decrypt(resultSet.getString("detail")),
                    resultSet.getString("path"),
                    resultSet.getString("display_name"),
                    deletedAt,
                    deletedAt.plusSeconds(KEEP_DAYS * 86_400L));
        });
    }

    /** Puts back the latest delete of this record: the Undo after a delete. */
    @Transactional
    public Restored restoreLatest(TrashKind kind, long recordId) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM trash_item WHERE kind = ? AND record_id = ? ORDER BY deleted_at DESC, id DESC LIMIT 1
                """, Long.class, kind.name(), recordId);
        if (ids.isEmpty()) {
            throw new NotFoundException("That isn't in the Trash");
        }
        return restore(ids.getFirst());
    }

    @Transactional
    public Restored restore(long itemId) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT kind, record_id, label, contents, path FROM trash_item WHERE id = ?", itemId);
        if (found.isEmpty()) {
            throw new NotFoundException("That isn't in the Trash any more");
        }
        Map<String, Object> item = found.getFirst();
        TrashKind kind = TrashKind.valueOf((String) item.get("kind"));
        long recordId = ((Number) item.get("record_id")).longValue();
        String label = encryptor.decrypt((String) item.get("label"));
        Snapshot snapshot;
        try {
            snapshot = json.readValue(encryptor.decrypt((String) item.get("contents")), Snapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The deleted record couldn't be read", exception);
        }

        List<Row> rows = new ArrayList<>(snapshot.rows());
        rows.sort(Comparator.comparingInt((Row row) -> SyncTables.position(row.table())).thenComparingLong(Row::id));
        try {
            for (Row row : rows) {
                boolean root = row.table().equals(kind.table) && row.id() == recordId;
                insertAgain(row, root, label);
            }
            for (Unlink unlink : snapshot.unlinks()) {
                if (exists(unlink.table(), unlink.id()) && exists(parentTable(unlink), unlink.parent())) {
                    jdbc.update("UPDATE " + unlink.table() + " SET " + unlink.column() + " = ? WHERE id = ? AND "
                            + unlink.column() + " IS NULL", unlink.parent(), unlink.id());
                }
            }
        } catch (DataIntegrityViolationException clash) {
            throw new ConflictException("Something added since then clashes with it (the same email, or the same "
                    + "candidate in the same search). Change or delete that one, then restore this.");
        }
        jdbc.update("DELETE FROM trash_item WHERE id = ?", itemId);
        return new Restored(kind, recordId, label, (String) item.get("path"));
    }

    /** Removes items older than the keeping period, everywhere (the removal syncs). */
    @Scheduled(initialDelayString = "${app.trash.purge-initial-delay-ms:300000}",
            fixedDelayString = "${app.trash.purge-interval-ms:21600000}")
    public void purge() {
        jdbc.update("DELETE FROM trash_item WHERE deleted_at < now() - ? * interval '1 day'", KEEP_DAYS);
    }

    // Saving --------------------------------------------------------------------------

    /** Adds these rows and, through cascading foreign keys, the rows deleted along with them. */
    private void collect(String table, List<Long> ids, Snapshot snapshot, Set<String> seen) {
        List<Long> fresh = ids.stream().filter(id -> seen.add(table + ":" + id)).toList();
        if (fresh.isEmpty()) {
            return;
        }
        readRows(table, fresh).forEach((id, row) -> snapshot.rows().add(new Row(table, id, row)));
        String idList = fresh.stream().map(String::valueOf).collect(Collectors.joining(","));
        for (ForeignKey key : catalog.childrenOf(table)) {
            if (key.onDelete() == OnDelete.RESTRICT) {
                continue;
            }
            List<long[]> children = jdbc.query(
                    "SELECT id, " + key.column() + " AS parent FROM " + key.child()
                            + " WHERE " + key.column() + " IN (" + idList + ")",
                    (resultSet, row) -> new long[] {resultSet.getLong("id"), resultSet.getLong("parent")});
            if (key.onDelete() == OnDelete.CASCADE) {
                collect(key.child(), children.stream().map(child -> child[0]).toList(), snapshot, seen);
            } else {
                for (long[] child : children) {
                    snapshot.unlinks().add(new Unlink(key.child(), child[0], key.column(), child[1]));
                }
            }
        }
    }

    /** Rows with every column as text, exactly as PostgreSQL prints it. */
    private Map<Long, Map<String, String>> readRows(String table, List<Long> ids) {
        Map<String, String> columns = catalog.columns(table);
        Map<Long, Map<String, String>> rows = new LinkedHashMap<>();
        if (ids.isEmpty() || columns.isEmpty()) {
            return rows;
        }
        String select = columns.keySet().stream().map(column -> column + "::text AS " + column)
                .collect(Collectors.joining(", "));
        String idList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.query("SELECT " + select + " FROM " + table + " WHERE id IN (" + idList + ") ORDER BY id", resultSet -> {
            Map<String, String> row = new LinkedHashMap<>();
            for (String column : columns.keySet()) {
                row.put(column, resultSet.getString(column));
            }
            rows.put(Long.parseLong(row.get("id")), row);
        });
        return rows;
    }

    /** The name and one line of detail shown in the Trash, and where the record opens. */
    private Description describe(TrashKind kind, long id, Map<String, String> root, Snapshot snapshot) {
        String with = alsoRemoved(kind, snapshot);
        return switch (kind) {
            case COMPANY -> new Description(text(root.get("name")), joined("Company", with), "/companies/" + id);
            case CONTACT -> new Description(text(root.get("name")),
                    joined(at(text(root.get("title")), name("company", root.get("company_id"))), with), "/contacts/" + id);
            case CANDIDATE -> new Description(text(root.get("name")),
                    joined(at(text(root.get("current_title")), text(root.get("current_company"))), with), "/candidates/" + id);
            case SEARCH -> new Description(text(root.get("title")),
                    joined("Search at " + name("company", root.get("company_id")), with), "/searches/" + id);
            case PLACEMENT -> new Description(name("candidate", root.get("candidate_id")),
                    joined("Placement: " + title(root.get("search_id")), with), "/placements");
            case FILE -> new Description(text(root.get("file_name")),
                    "File on " + name("candidate", root.get("candidate_id")), "/candidates/" + root.get("candidate_id"));
        };
    }

    /** "With 12 history entries and 2 searches." for what went with the record. */
    private static String alsoRemoved(TrashKind kind, Snapshot snapshot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row row : snapshot.rows()) {
            if (!row.table().equals(kind.table)) {
                counts.merge(row.table(), 1, Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((table, count) -> parts.add(switch (table) {
            case "activity" -> count + (count == 1 ? " history entry" : " history entries");
            case "pipeline_entry" -> count + (count == 1 ? " search" : " searches");
            case "candidate_file" -> count + (count == 1 ? " file" : " files");
            default -> count + " related " + (count == 1 ? "record" : "records");
        }));
        if (parts.isEmpty()) {
            return null;
        }
        String list = parts.size() == 1 ? parts.getFirst()
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
        return "With " + list + ".";
    }

    private String text(String stored) {
        return stored == null ? null : encryptor.decrypt(stored);
    }

    private String name(String table, String id) {
        if (id == null) {
            return "";
        }
        List<String> names = jdbc.queryForList("SELECT name FROM " + table + " WHERE id = ?::bigint", String.class, id);
        return names.isEmpty() ? "" : encryptor.decrypt(names.getFirst());
    }

    private String title(String searchId) {
        if (searchId == null) {
            return "";
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.title, c.name FROM job_search s JOIN company c ON c.id = s.company_id WHERE s.id = ?::bigint",
                searchId);
        if (rows.isEmpty()) {
            return "";
        }
        return encryptor.decrypt((String) rows.getFirst().get("title")) + " at "
                + encryptor.decrypt((String) rows.getFirst().get("name"));
    }

    private static String at(String what, String where) {
        boolean hasWhat = what != null && !what.isBlank();
        boolean hasWhere = where != null && !where.isBlank();
        if (hasWhat && hasWhere) {
            return what + " at " + where;
        }
        return hasWhat ? what : hasWhere ? where : null;
    }

    private static String joined(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null) {
            return first.endsWith(".") ? first : first + ".";
        }
        return (first.endsWith(".") ? first : first + ".") + " " + second;
    }

    // Restoring -------------------------------------------------------------------------

    private void insertAgain(Row row, boolean root, String label) {
        if (exists(row.table(), row.id())) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>(row.row());
        for (ForeignKey key : catalog.parentsOf(row.table())) {
            String parent = values.get(key.column());
            if (parent == null || exists(key.parent(), Long.parseLong(parent))) {
                continue;
            }
            if (key.onDelete() == OnDelete.SET_NULL) {
                values.put(key.column(), null);
            } else if (root) {
                throw new ConflictException(missingParent(label, key.parent(), Long.parseLong(parent)));
            } else {
                // It would have gone with its other parent anyway (a note on a search deleted since).
                return;
            }
        }
        Map<String, String> types = catalog.columns(row.table());
        List<String> columns = values.keySet().stream().filter(types::containsKey).toList();
        String insert = "INSERT INTO " + row.table() + " (" + String.join(", ", columns) + ") VALUES ("
                + columns.stream().map(column -> "?::" + types.get(column)).collect(Collectors.joining(", ")) + ")";
        jdbc.update(insert, columns.stream().map(values::get).toArray());
    }

    /** Why a record can't come back yet: what it belongs to is gone too. */
    private String missingParent(String label, String parentTable, long parentId) {
        TrashKind parentKind = TrashKind.forTable(parentTable);
        if (parentKind != null) {
            List<String> labels = jdbc.queryForList("""
                    SELECT label FROM trash_item WHERE kind = ? AND record_id = ? ORDER BY deleted_at DESC LIMIT 1
                    """, String.class, parentKind.name(), parentId);
            if (!labels.isEmpty()) {
                return "Restore " + encryptor.decrypt(labels.getFirst()) + " first. It's in the Trash too.";
            }
        }
        String what = switch (parentTable) {
            case "company" -> "company";
            case "candidate" -> "candidate";
            case "job_search" -> "search";
            case "contact" -> "contact";
            default -> "record it belongs to";
        };
        return label + " can't be restored: its " + what + " was deleted for good.";
    }

    private String parentTable(Unlink unlink) {
        return catalog.parentsOf(unlink.table()).stream()
                .filter(key -> key.column().equals(unlink.column()))
                .map(ForeignKey::parent)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown link " + unlink.table() + "." + unlink.column()));
    }

    private boolean exists(String table, long id) {
        Boolean found = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(found);
    }
}
