package com.janemichael.jmrecruitingcrm.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gives each computer its own block of ids (computer 2 uses 2,000,000,001 and up, computer 3
 * uses 3,000,000,001 and up), so records created on different computers never share an id.
 */
@Component
public class IdRanges {

    private static final Logger log = LoggerFactory.getLogger(IdRanges.class);

    private final JdbcTemplate jdbc;

    public IdRanges(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void apply(int nodeId) {
        if (nodeId <= 0) {
            return;
        }
        long low = nodeId * SyncTables.RANGE;
        long high = low + SyncTables.RANGE;

        for (String table : SyncTables.GENERATED_IDS) {
            Long highest = jdbc.queryForObject(
                    "SELECT max(id) FROM " + table + " WHERE id >= ? AND id < ?", Long.class, low, high);
            long next = highest == null ? low + 1 : highest + 1;
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + next);
        }
        log.info("New records on this computer get ids from {}", low + 1);
    }
}
