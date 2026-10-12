package com.janemichael.jmrecruitingcrm.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Which computer this is. Computers are numbered 1, 2, 3 and so on as they join the group;
 * a copy that isn't part of a group (a server, or a desktop before setup) is 0.
 */
@Component
public class SyncIdentity {

    public record Self(int nodeId, String name) {
    }

    private final JdbcTemplate jdbc;
    private final IdRanges idRanges;

    public SyncIdentity(JdbcTemplate jdbc, IdRanges idRanges) {
        this.jdbc = jdbc;
        this.idRanges = idRanges;
    }

    /** Read each time (it's one tiny row), so it is never out of date. */
    public Optional<Self> self() {
        List<Self> rows = jdbc.query("SELECT node_id, name FROM sync_self LIMIT 1",
                (resultSet, row) -> new Self(resultSet.getInt("node_id"), resultSet.getString("name")));
        return rows.stream().findFirst();
    }

    public boolean isEnabled() {
        return self().isPresent();
    }

    public int nodeId() {
        return self().map(Self::nodeId).orElse(0);
    }

    /** Makes this copy computer {@code nodeId}, listed in the shared directory of computers. */
    @Transactional
    public void become(int nodeId, String name) {
        jdbc.update("DELETE FROM sync_self");
        jdbc.update("INSERT INTO sync_self (node_id, name) VALUES (?, ?)", nodeId, name);
        jdbc.update("INSERT INTO sync_node (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", nodeId, name);
        idRanges.apply(nodeId);
    }
}
