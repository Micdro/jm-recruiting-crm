package com.janemichael.jmrecruitingcrm.undo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.sync.SyncCatalog;
import com.janemichael.jmrecruitingcrm.sync.SyncTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recent changes: each person's actions from the last day on this computer, and undoing them.
 *
 * <p>A trigger on the shared tables keeps every row's state before and after each change made here
 * (see V23__undo.sql). An action names the transaction that made it. Undoing it puts every row it
 * changed back as it was before: rows it added are removed, rows it removed come back, rows it
 * changed get their old values. That only happens if none of those rows has changed since, here or
 * on another computer; otherwise nothing is touched. The undo is itself an ordinary change, so it
 * syncs to the other computers.
 */
@Service
public class UndoLog {

    private static final Logger log = LoggerFactory.getLogger(UndoLog.class);

    public static final Duration KEPT = Duration.ofHours(24);
    public static final int SHOWN = 20;
    /** Rows of a transaction nobody named as an action (a background job, an undo by its own button). */
    static final Duration UNCLAIMED = Duration.ofMinutes(10);
    /** The shared tables the trigger watches: all but the computers and the team's accounts. */
    static final Set<String> SKIPPED = Set.of("sync_node", "app_user");
    /** Bookkeeping that a later change can touch without changing what someone sees. */
    private static final Set<String> IGNORED = Set.of("updated_at");

    public static final String CHANGED_SINCE =
            "Something in this has changed since, here or on another computer, so it can't be undone. "
                    + "Change it back by hand.";

    /** What the record is, so the page can link to it and label it. */
    public enum Kind { COMPANY, CONTACT, CANDIDATE, SEARCH, PLACEMENT, TEMPLATE, POST, OTHER }

    /** One action, newest first in the list. {@code recordId} is null when there's nothing to open. */
    public record Action(long id, String label, Kind kind, Long recordId, Instant at, Instant undoneAt) {
    }

    /** @param already true when it had been put back another way (its own Undo button, say) */
    public record Undone(long id, String label, Kind kind, Long recordId, boolean already) {
    }

    /** One row's state before an action and after it. Null means it didn't exist. */
    record Touched(String table, long id, String before, String after) {
    }

    private final JdbcTemplate jdbc;
    private final SyncCatalog catalog;
    private final FieldEncryptor encryptor;
    private final CurrentUserService currentUser;
    private final AppUserRepository users;
    private final ObjectMapper json = new ObjectMapper();

    public UndoLog(JdbcTemplate jdbc, SyncCatalog catalog, FieldEncryptor encryptor, CurrentUserService currentUser,
                   AppUserRepository users) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.encryptor = encryptor;
        this.currentUser = currentUser;
        this.users = users;
    }

    // Recording ---------------------------------------------------------------------------

    /**
     * Names the current transaction as an action of the signed-in person, so they can undo it.
     * Call it from inside the change's transaction. Does nothing when nobody is signed in (a
     * background job) or the transaction already has a name.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String label, Kind kind, Long recordId) {
        Long userId = signedInUserId();
        if (userId == null || label == null || label.isBlank()) {
            return;
        }
        Long txid = jdbc.queryForObject("SELECT pg_current_xact_id()::text::bigint", Long.class);
        jdbc.update("""
                INSERT INTO undo_action (txid, user_id, label, kind, record_id) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (txid) DO NOTHING
                """, txid, userId, encryptor.encrypt(label.trim()), kind.name(), recordId);
    }

    /** "1 contact", "3 contacts", for labels. */
    public static String count(long value, String one, String many) {
        return value + " " + (value == 1 ? one : many);
    }

    private Long signedInUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return null;
        }
        return users.findByEmailIgnoreCase(authentication.getName()).map(AppUser::getId).orElse(null);
    }

    // Listing -----------------------------------------------------------------------------

    /** The signed-in person's last few actions on this computer, from the last day. */
    @Transactional(readOnly = true)
    public List<Action> list() {
        AppUser me = currentUser.get();
        Timestamp since = Timestamp.from(Instant.now().minus(KEPT));
        return jdbc.query("""
                SELECT a.txid, a.label, a.kind, a.record_id, a.created_at, a.undone_at FROM undo_action a
                WHERE a.user_id = ? AND a.created_at > ?
                  AND (a.undone_at IS NOT NULL OR EXISTS (SELECT 1 FROM undo_row r WHERE r.txid = a.txid))
                ORDER BY a.created_at DESC, a.txid DESC LIMIT ?
                """, (row, number) -> new Action(
                row.getLong("txid"),
                encryptor.decrypt(row.getString("label")),
                kind(row.getString("kind")),
                (Long) row.getObject("record_id"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("undone_at") == null ? null : row.getTimestamp("undone_at").toInstant()),
                me.getId(), since, SHOWN);
    }

    private static Kind kind(String value) {
        try {
            return Kind.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return Kind.OTHER;
        }
    }

    // Undoing -----------------------------------------------------------------------------

    /** Puts back everything the action changed, if nothing has changed since. */
    @Transactional
    public Undone undo(long id) {
        AppUser me = currentUser.get();
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT txid, user_id, label, kind, record_id, created_at, undone_at FROM undo_action
                WHERE txid = ? FOR UPDATE
                """, id);
        if (found.isEmpty() || ((Number) found.getFirst().get("user_id")).longValue() != me.getId()) {
            throw new NotFoundException("That change isn't in your recent changes on this computer.");
        }
        Map<String, Object> action = found.getFirst();
        String label = encryptor.decrypt((String) action.get("label"));
        Kind kind = kind((String) action.get("kind"));
        Long recordId = action.get("record_id") == null ? null : ((Number) action.get("record_id")).longValue();
        if (action.get("undone_at") != null) {
            throw new ConflictException("That's already undone.");
        }
        Instant at = ((Timestamp) action.get("created_at")).toInstant();
        if (at.plus(KEPT).isBefore(Instant.now())) {
            throw new ConflictException("That change is more than a day old, so it can't be undone here.");
        }

        List<Touched> touched = touched(id);
        boolean asAfter = touched.stream().allMatch(row -> matches(row, row.after()));
        boolean asBefore = !touched.isEmpty() && touched.stream().allMatch(row -> matches(row, row.before()));
        if (!asAfter && !asBefore) {
            throw new ConflictException(CHANGED_SINCE);
        }
        if (asAfter && !asBefore) {
            if (pointedAtSince(touched)) {
                throw new ConflictException(CHANGED_SINCE);
            }
            // This undo isn't recorded as a change of its own.
            jdbc.queryForObject("SELECT set_config('jmcrm.undo_off', 'on', true)", String.class);
            try {
                putBack(touched);
            } catch (DataIntegrityViolationException clash) {
                throw new ConflictException("Something changed since is in the way (the same email added again, "
                        + "or the company it belongs to deleted), so it can't be undone.");
            }
            jdbc.queryForObject("SELECT set_config('jmcrm.undo_off', '', true)", String.class);
        }
        jdbc.update("UPDATE undo_action SET undone_at = now() WHERE txid = ?", id);
        jdbc.update("DELETE FROM undo_row WHERE txid = ?", id);
        log.info("Undid change {} ({} rows)", id, touched.size());
        return new Undone(id, label, kind, recordId, asBefore);
    }

    /** Each row the action changed: as it was before its first change and after its last. */
    List<Touched> touched(long txid) {
        Map<String, String[]> rows = new LinkedHashMap<>();
        jdbc.query("SELECT table_name, row_id, old_row::text AS old_row, new_row::text AS new_row FROM undo_row "
                + "WHERE txid = ? ORDER BY seq", row -> {
            String key = row.getString("table_name") + ":" + row.getLong("row_id");
            String[] states = rows.get(key);
            if (states == null) {
                rows.put(key, new String[] {row.getString("old_row"), row.getString("new_row")});
            } else {
                states[1] = row.getString("new_row");
            }
        }, txid);
        List<Touched> touched = new ArrayList<>();
        rows.forEach((key, states) -> {
            int colon = key.lastIndexOf(':');
            String table = key.substring(0, colon);
            // Added and removed again in the same action: nothing to put back.
            if (states[0] != null || states[1] != null) {
                touched.add(new Touched(table, Long.parseLong(key.substring(colon + 1)), states[0], states[1]));
            }
        });
        return touched;
    }

    /** Whether the row is now as given (absent, for null), leaving bookkeeping aside. */
    private boolean matches(Touched row, String state) {
        requireKnown(row.table());
        if (state == null) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM " + row.table() + " WHERE id = ?",
                    Integer.class, row.id());
            return count != null && count == 0;
        }
        // The given state goes through the row type too, so times read the same whatever time zone wrote them.
        String given = "to_jsonb(jsonb_populate_record(NULL::" + row.table() + ", ?::jsonb))::text";
        List<Map<String, Object>> found = jdbc.queryForList("SELECT to_jsonb(t)::text AS current_row, " + given
                + " AS given_row FROM " + row.table() + " t WHERE id = ?", state, row.id());
        return !found.isEmpty() && same((String) found.getFirst().get("current_row"),
                (String) found.getFirst().get("given_row"));
    }

    /**
     * Whether two states of a row say the same, field by field. Encrypted fields compare by what they
     * say, since every save encrypts them afresh.
     */
    boolean same(String now, String given) {
        JsonNode current = read(now);
        JsonNode wanted = read(given);
        Set<String> names = new HashSet<>();
        current.fieldNames().forEachRemaining(names::add);
        wanted.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (IGNORED.contains(name)) {
                continue;
            }
            JsonNode a = current.get(name);
            JsonNode b = wanted.get(name);
            if (Objects.equals(a, b)) {
                continue;
            }
            boolean bothEncrypted = a != null && b != null && a.isTextual() && b.isTextual()
                    && FieldEncryptor.isEncrypted(a.asText()) && FieldEncryptor.isEncrypted(b.asText());
            if (!bothEncrypted || !Objects.equals(encryptor.decrypt(a.asText()), encryptor.decrypt(b.asText()))) {
                return false;
            }
        }
        return true;
    }

    private JsonNode read(String row) {
        try {
            return json.readTree(row);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("A recorded change couldn't be read", unreadable);
        }
    }

    /**
     * Whether something added since points at a row this action added (a note on a new contact, say),
     * which undoing the action would remove with it.
     */
    private boolean pointedAtSince(List<Touched> touched) {
        Set<String> part = new HashSet<>();
        touched.forEach(row -> part.add(row.table() + ":" + row.id()));
        for (Touched row : touched) {
            if (row.before() != null) {
                continue;
            }
            for (SyncCatalog.ForeignKey key : catalog.childrenOf(row.table())) {
                List<Long> children = jdbc.queryForList("SELECT id FROM " + key.child() + " WHERE \"" + key.column()
                        + "\" = ?", Long.class, row.id());
                if (children.stream().anyMatch(child -> !part.contains(key.child() + ":" + child))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Writes the rows back: added and changed rows parents first, then removes the rows the action
     * added, children first, so every link points at something that's there.
     */
    private void putBack(List<Touched> touched) {
        Comparator<Touched> parentsFirst = Comparator.comparingInt(row -> SyncTables.position(row.table()));
        List<Touched> sorted = touched.stream().sorted(parentsFirst).toList();
        for (Touched row : sorted) {
            if (row.before() == null) {
                continue;
            }
            List<String> columns = catalog.columns(row.table()).keySet().stream().filter(column -> !column.equals("id"))
                    .toList();
            String list = String.join(", ", columns);
            String source = "jsonb_populate_record(NULL::" + row.table() + ", ?::jsonb)";
            if (row.after() == null) {
                jdbc.update("INSERT INTO " + row.table() + " (id, " + list + ") SELECT id, " + list + " FROM " + source,
                        row.before());
            } else {
                jdbc.update("UPDATE " + row.table() + " SET (" + list + ") = (SELECT " + list + " FROM " + source
                        + ") WHERE id = ?", row.before(), row.id());
            }
        }
        List<Touched> added = new ArrayList<>(sorted.stream().filter(row -> row.before() == null).toList());
        added.sort(parentsFirst.reversed());
        for (Touched row : added) {
            jdbc.update("DELETE FROM " + row.table() + " WHERE id = ?", row.id());
        }
    }

    private static void requireKnown(String table) {
        if (!SyncTables.isShared(table) || SKIPPED.contains(table)) {
            throw new IllegalStateException("Not a table changes are recorded for: " + table);
        }
    }

    // Keeping it small ----------------------------------------------------------------------

    /** Every 10 minutes: forgets what's more than a day old, and rows that aren't part of an action. */
    @Scheduled(initialDelayString = "${app.undo.initial-delay-ms:300000}", fixedDelayString = "${app.undo.interval-ms:600000}")
    public void prune() {
        try {
            Timestamp dayAgo = Timestamp.from(Instant.now().minus(KEPT));
            Timestamp unclaimed = Timestamp.from(Instant.now().minus(UNCLAIMED));
            jdbc.update("DELETE FROM undo_action WHERE created_at < ?", dayAgo);
            jdbc.update("""
                    DELETE FROM undo_row r WHERE r.created_at < ?
                       OR (r.created_at < ? AND NOT EXISTS (SELECT 1 FROM undo_action a WHERE a.txid = r.txid))
                    """, dayAgo, unclaimed);
        } catch (RuntimeException problem) {
            log.warn("Could not clear out old recent changes: {}", problem.toString());
        }
    }

    /** After a backup is restored, nothing from before can be undone. Call it in the restore's transaction. */
    public void forgetAll() {
        jdbc.update("DELETE FROM undo_action");
        jdbc.update("DELETE FROM undo_row");
    }
}
