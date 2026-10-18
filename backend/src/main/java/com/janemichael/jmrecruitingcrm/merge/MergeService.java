package com.janemichael.jmrecruitingcrm.merge;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineStage;
import com.janemichael.jmrecruitingcrm.sync.SyncCatalog;
import com.janemichael.jmrecruitingcrm.trash.TrashKind;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import com.janemichael.jmrecruitingcrm.undo.UndoLog;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Merges two records of the same kind that are the same person or company: everything that points
 * at the duplicate (history, searches, pipeline, placements, files, contacts) moves to the record
 * kept, the kept record's empty fields are filled from the duplicate, notes are combined, and the
 * duplicate goes to the Trash with its details. The kept record's history says what was merged in.
 *
 * <p>References are found from the database's foreign keys, so tables added later move too. The
 * only clashes are a candidate in the same search twice, where the entry that got further is
 * kept, and a placement in the same search twice, which has to be sorted out by hand first.
 */
@Service
@Transactional
public class MergeService {

    public enum Kind {
        CONTACT("contact", TrashKind.CONTACT, List.of("email", "phone", "title", "linkedin_url")),
        COMPANY("company", TrashKind.COMPANY, List.of("website", "location", "linkedin_profile")),
        CANDIDATE("candidate", TrashKind.CANDIDATE,
                List.of("email", "phone", "linkedin_url", "current_title", "current_company", "location"));

        final String table;
        final TrashKind trashKind;
        /** Details worth noting in the history when the duplicate had a different value. */
        final List<String> shown;

        Kind(String table, TrashKind trashKind, List<String> shown) {
            this.table = table;
            this.trashKind = trashKind;
            this.shown = shown;
        }
    }

    public record Request(
            @NotNull(message = "Choose what to merge") Kind kind,
            @NotNull(message = "Choose the record to keep") Long keepId,
            @NotNull(message = "Choose the record to merge in") Long mergeId
    ) {
    }

    public record Result(Kind kind, long keptId, String keptName, String mergedName) {
    }

    /** Identity and bookkeeping, and notes, which are combined rather than copied. */
    private static final Set<String> NOT_COPIED = Set.of("id", "created_at", "updated_at", "notes");

    /** Where the later of the two dates wins, rather than the kept record's. */
    private static final Set<String> LATEST = Set.of("last_contacted_date");

    private static final Map<String, String> LABELS = Map.of(
            "email", "email",
            "phone", "phone",
            "title", "title",
            "linkedin_url", "LinkedIn",
            "linkedin_profile", "LinkedIn",
            "current_title", "title",
            "current_company", "employer",
            "location", "location",
            "website", "website");

    private final JdbcTemplate jdbc;
    private final SyncCatalog catalog;
    private final FieldEncryptor encryptor;
    private final TrashService trash;
    private final ActivityLogger activityLogger;
    private final CurrentUserService currentUserService;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final CandidateRepository candidateRepository;
    private final EntityManager entityManager;
    private final UndoLog undoLog;

    public MergeService(
            JdbcTemplate jdbc,
            SyncCatalog catalog,
            FieldEncryptor encryptor,
            TrashService trash,
            ActivityLogger activityLogger,
            CurrentUserService currentUserService,
            ContactRepository contactRepository,
            CompanyRepository companyRepository,
            CandidateRepository candidateRepository,
            EntityManager entityManager,
            UndoLog undoLog
    ) {
        this.undoLog = undoLog;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.encryptor = encryptor;
        this.trash = trash;
        this.activityLogger = activityLogger;
        this.currentUserService = currentUserService;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.candidateRepository = candidateRepository;
    }

    public Result merge(Request request) {
        Kind kind = request.kind();
        long keep = request.keepId();
        long merge = request.mergeId();
        if (keep == merge) {
            throw new BadRequestException("Choose two different records to merge");
        }
        // The merge works in SQL, so anything not yet written in this transaction is written first.
        entityManager.flush();
        Map<String, String> kept = row(kind.table, keep);
        Map<String, String> other = row(kind.table, merge);
        if (kept == null || other == null) {
            throw new NotFoundException("One of the records to merge wasn't found. It may have been deleted.");
        }
        AppUser user = currentUserService.get();

        if (kind == Kind.CANDIDATE) {
            resolveSameSearch(keep, merge);
        }

        // Everything that points at the duplicate now points at the record kept.
        for (SyncCatalog.ForeignKey key : catalog.childrenOf(kind.table)) {
            jdbc.update("UPDATE " + key.child() + " SET " + quote(key.column()) + " = ? WHERE " + quote(key.column()) + " = ?",
                    keep, merge);
        }

        // Its details wait in the Trash for 30 days; its history is now on the record kept.
        trash.capture(kind.trashKind, merge);
        jdbc.update("DELETE FROM " + kind.table + " WHERE id = ?", merge);

        fillIn(kind.table, keep, kept, other);
        if (kind == Kind.CANDIDATE) {
            // A placement that came with the duplicate makes the candidate placed.
            jdbc.update("UPDATE candidate SET status = 'PLACED' WHERE id = ? AND status <> 'PLACED' "
                    + "AND EXISTS (SELECT 1 FROM placement WHERE candidate_id = ?)", keep, keep);
        }

        String summary = summary(kind, kept, other);
        switch (kind) {
            case CONTACT -> activityLogger.forContact(contactRepository.findWithDetailsById(keep).orElseThrow(),
                    ActivityType.STATUS_CHANGE, false, summary, user);
            case COMPANY -> activityLogger.forCompany(companyRepository.findWithOwnerById(keep).orElseThrow(),
                    ActivityType.STATUS_CHANGE, summary, user);
            case CANDIDATE -> activityLogger.forCandidate(candidateRepository.findById(keep).orElseThrow(), null,
                    ActivityType.STATUS_CHANGE, false, summary, user);
        }
        // Records loaded earlier in this transaction no longer match the rows; load them afresh.
        entityManager.flush();
        entityManager.clear();
        Result result = new Result(kind, keep, encryptor.decrypt(kept.get("name")), encryptor.decrypt(other.get("name")));
        undoLog.record("Merged " + result.mergedName() + " into " + result.keptName(), switch (kind) {
            case CONTACT -> UndoLog.Kind.CONTACT;
            case COMPANY -> UndoLog.Kind.COMPANY;
            case CANDIDATE -> UndoLog.Kind.CANDIDATE;
        }, keep);
        return result;
    }

    /**
     * Both candidates in the same search: keep the pipeline entry that got further (an active one
     * over a closed one) and drop the other. Two placements in one search can't be combined.
     */
    private void resolveSameSearch(long keep, long merge) {
        List<String> placed = jdbc.queryForList("""
                SELECT s.title FROM placement a
                JOIN placement b ON b.search_id = a.search_id
                JOIN job_search s ON s.id = a.search_id
                WHERE a.candidate_id = ? AND b.candidate_id = ?
                """, String.class, keep, merge);
        if (!placed.isEmpty()) {
            throw new ConflictException("Both have a placement in " + encryptor.decrypt(placed.getFirst())
                    + ". Delete one of the placements, then merge.");
        }

        List<Map<String, Object>> pairs = jdbc.queryForList("""
                SELECT a.id AS kept_entry, a.stage AS kept_stage, b.id AS other_entry, b.stage AS other_stage
                FROM pipeline_entry a JOIN pipeline_entry b ON b.search_id = a.search_id
                WHERE a.candidate_id = ? AND b.candidate_id = ?
                """, keep, merge);
        for (Map<String, Object> pair : pairs) {
            int keptRank = rank(PipelineStage.valueOf((String) pair.get("kept_stage")));
            int otherRank = rank(PipelineStage.valueOf((String) pair.get("other_stage")));
            Object drop = otherRank > keptRank ? pair.get("kept_entry") : pair.get("other_entry");
            jdbc.update("DELETE FROM pipeline_entry WHERE id = ?", drop);
        }
    }

    /** How far a candidate got in a search: placed is furthest, and any closed stage counts least. */
    static int rank(PipelineStage stage) {
        if (stage == PipelineStage.PLACED) {
            return 100;
        }
        return stage.isActive() ? stage.ordinal() : -1;
    }

    /** Fills the kept record's empty fields from the duplicate, and combines their notes. */
    private void fillIn(String table, long keep, Map<String, String> kept, Map<String, String> other) {
        Map<String, String> types = catalog.columns(table);
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        for (Map.Entry<String, String> column : types.entrySet()) {
            String name = column.getKey();
            String value = other.get(name);
            if (NOT_COPIED.contains(name) || value == null) {
                continue;
            }
            if (LATEST.contains(name)) {
                sets.add(quote(name) + " = GREATEST(" + quote(name) + ", ?::" + column.getValue() + ")");
            } else if (kept.get(name) == null) {
                sets.add(quote(name) + " = ?::" + column.getValue());
            } else {
                continue;
            }
            args.add(value);
        }

        if (types.containsKey("notes")) {
            String keptNotes = clean(encryptor.decrypt(kept.get("notes")));
            String otherNotes = clean(encryptor.decrypt(other.get("notes")));
            if (otherNotes != null && !otherNotes.equals(keptNotes)) {
                sets.add("notes = ?");
                args.add(encryptor.encrypt(keptNotes == null ? otherNotes : keptNotes + "\n\n" + otherNotes));
            }
        }
        if (types.containsKey("updated_at")) {
            sets.add("updated_at = now()");
        }
        if (sets.isEmpty()) {
            return;
        }
        args.add(keep);
        jdbc.update("UPDATE " + table + " SET " + String.join(", ", sets) + " WHERE id = ?", args.toArray());
    }

    /** "Merged in Jane Doe (it had email jane@old.example, title CTO)", for the kept record's history. */
    private String summary(Kind kind, Map<String, String> kept, Map<String, String> other) {
        List<String> differed = new ArrayList<>();
        for (String column : kind.shown) {
            String mine = clean(encryptor.decrypt(kept.get(column)));
            String theirs = clean(encryptor.decrypt(other.get(column)));
            if (mine != null && theirs != null && !mine.equalsIgnoreCase(theirs)) {
                differed.add(LABELS.getOrDefault(column, column) + " " + theirs);
            }
        }
        if (kind == Kind.CONTACT && other.get("company_id") != null && !other.get("company_id").equals(kept.get("company_id"))) {
            List<String> names = jdbc.queryForList("SELECT name FROM company WHERE id = ?::bigint", String.class,
                    other.get("company_id"));
            if (!names.isEmpty()) {
                differed.add("company " + encryptor.decrypt(names.getFirst()));
            }
        }
        String name = encryptor.decrypt(other.get("name"));
        return "Merged in " + name + (differed.isEmpty() ? "" : " (it had " + String.join(", ", differed) + ")");
    }

    /** A row's columns as text, ready to be cast back, or null when there's no such row. */
    private Map<String, String> row(String table, long id) {
        String select = catalog.columns(table).keySet().stream()
                .map(column -> quote(column) + "::text AS " + quote(column))
                .collect(Collectors.joining(", "));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + select + " FROM " + table + " WHERE id = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        rows.getFirst().forEach((column, value) -> values.put(column, value == null ? null : value.toString()));
        return values;
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
