package com.janemichael.jmrecruitingcrm.trash;

/** What can be deleted to the Trash, and the table each one lives in. */
public enum TrashKind {
    COMPANY("company"),
    CONTACT("contact"),
    CANDIDATE("candidate"),
    SEARCH("job_search"),
    PLACEMENT("placement"),
    FILE("candidate_file");

    final String table;

    TrashKind(String table) {
        this.table = table;
    }

    static TrashKind forTable(String table) {
        for (TrashKind kind : values()) {
            if (kind.table.equals(table)) {
                return kind;
            }
        }
        return null;
    }
}
