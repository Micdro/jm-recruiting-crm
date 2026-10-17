package com.janemichael.jmrecruitingcrm.sync;

import java.util.List;
import java.util.Map;

/** Which tables are shared between computers, and the rules that are specific to some of them. */
public final class SyncTables {

    /** Parents before children, so a batch can be applied in this order (and deletes in reverse). */
    public static final List<String> ORDER = List.of(
            "sync_node",
            "app_user",
            "message_template",
            "content_post",
            "firm_setting",
            "company",
            "contact",
            "candidate",
            "candidate_file",
            "job_search",
            "pipeline_entry",
            "placement",
            "activity",
            "trash_item");

    /** Tables whose ids are generated locally. Each computer generates them in its own range. */
    public static final List<String> GENERATED_IDS = ORDER.subList(1, ORDER.size());

    /** Ids from computer N fall in [N * RANGE, (N + 1) * RANGE), so two computers never pick the same id. */
    public static final long RANGE = 1_000_000_000L;

    /**
     * Rows that must be unique by something other than their id. If two computers create the same
     * one (the same teammate's email, or the same candidate in the same search), the copy with the
     * lower id is kept everywhere.
     */
    public record NaturalKey(String where, List<String> columns) {
    }

    public static final Map<String, NaturalKey> NATURAL_KEYS = Map.of(
            "app_user", new NaturalKey("lower(email) = lower(?::text)", List.of("email")),
            "pipeline_entry", new NaturalKey("search_id = ?::bigint AND candidate_id = ?::bigint",
                    List.of("search_id", "candidate_id")),
            "placement", new NaturalKey("search_id = ?::bigint AND candidate_id = ?::bigint",
                    List.of("search_id", "candidate_id")),
            "firm_setting", new NaturalKey("name = ?::text", List.of("name")));

    private SyncTables() {
    }

    public static int position(String table) {
        int index = ORDER.indexOf(table);
        if (index < 0) {
            throw new IllegalArgumentException("Not a shared table: " + table);
        }
        return index;
    }

    public static boolean isShared(String table) {
        return ORDER.contains(table);
    }
}
