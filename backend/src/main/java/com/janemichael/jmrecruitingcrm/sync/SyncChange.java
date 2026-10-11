package com.janemichael.jmrecruitingcrm.sync;

import java.util.Map;

/**
 * The latest version of one shared row, as sent between computers.
 *
 * @param table   the table, one of {@link SyncTables#ORDER}
 * @param id      the row's id
 * @param version when it last changed, in microseconds since 1970 (UTC)
 * @param origin  the computer where that change was made (0 for a server or rows from before sync)
 * @param deleted whether the row was deleted
 * @param row     every column as PostgreSQL text, or null for deleted rows. Encrypted columns stay
 *                encrypted: every computer in the group shares the key.
 */
public record SyncChange(String table, long id, long version, int origin, boolean deleted, Map<String, String> row) {

    /** Last write wins. Equal times are settled by the higher computer number, so all copies agree. */
    public boolean isNewerThan(long otherVersion, int otherOrigin) {
        return version > otherVersion || (version == otherVersion && origin > otherOrigin);
    }

    public String key() {
        return table + ":" + id;
    }
}
