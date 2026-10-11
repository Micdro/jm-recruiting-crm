package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.sync.SyncClient.SyncException;
import com.janemichael.jmrecruitingcrm.sync.SyncIdentity.Self;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ChangesResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.JoinResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.NodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Keeps this computer's copy in step with the others. Every few seconds it asks each computer in
 * the group for what changed since last time and applies it. A computer that is off or offline is
 * skipped and caught up later.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    public record Peer(long id, String name, String address) {
    }

    public record PeerStatus(
            long id,
            String name,
            String address,
            Instant lastSuccess,
            Instant lastAttempt,
            String lastError,
            /** Measured by this computer's clock, so the page needn't compare clocks. */
            Long secondsSinceSuccess
    ) {
    }

    public record Status(boolean enabled, Integer nodeId, String name, List<PeerStatus> peers, int waiting) {
    }

    private final JdbcTemplate jdbc;
    private final SyncIdentity identity;
    private final SyncFeed feed;
    private final SyncApplier applier;
    private final SyncClient client;
    private final FieldEncryptor encryptor;
    private final int syncPort;

    public SyncService(
            JdbcTemplate jdbc,
            SyncIdentity identity,
            SyncFeed feed,
            SyncApplier applier,
            SyncClient client,
            FieldEncryptor encryptor,
            @Value("${app.sync.port:4781}") int syncPort
    ) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.feed = feed;
        this.applier = applier;
        this.client = client;
        this.encryptor = encryptor;
        this.syncPort = syncPort;
    }

    public int syncPort() {
        return syncPort;
    }

    // Answering other computers -------------------------------------------------

    /** Another computer joins the group through this one and gets the next computer number. */
    @Transactional
    public synchronized JoinResponse acceptJoin(String name, String remoteHost, int remotePort) {
        Self self = requireSelf();
        int nodeId = identity.nextNodeId();
        String cleanName = name == null || name.isBlank() ? "Computer " + nodeId : name.trim();
        jdbc.update("INSERT INTO sync_node (id, name, address) VALUES (?, ?, ?)",
                nodeId, truncate(cleanName, 100), address(remoteHost, remotePort));
        log.info("{} joined as computer {}", cleanName, nodeId);
        return new JoinResponse(SyncProtocol.VERSION, nodeId, new NodeInfo(self.nodeId(), self.name()));
    }

    /** Everything changed since the cursor. Also notes where the asking computer can be reached. */
    public ChangesResponse changesSince(long since, Long callerNode, String remoteHost, Integer remotePort) {
        Self self = requireSelf();
        if (callerNode != null && callerNode > 0 && remotePort != null && remotePort > 0) {
            String address = address(remoteHost, remotePort);
            jdbc.update("UPDATE sync_node SET address = ? WHERE id = ? AND address IS DISTINCT FROM ?",
                    address, callerNode, address);
        }
        SyncFeed.Feed changes = feed.since(since);
        return new ChangesResponse(SyncProtocol.VERSION, new NodeInfo(self.nodeId(), self.name()),
                changes.cursor(), changes.changes());
    }

    // Asking other computers -----------------------------------------------------

    @Scheduled(initialDelayString = "${app.sync.initial-delay-ms:3000}", fixedDelayString = "${app.sync.interval-ms:5000}")
    public void syncAll() {
        if (!identity.isEnabled() || !encryptor.isConfigured()) {
            return;
        }
        for (Peer peer : peers()) {
            syncWith(peer);
        }
    }

    public List<Peer> peers() {
        return jdbc.query("SELECT id, name, address FROM sync_node WHERE id <> ? AND address IS NOT NULL ORDER BY id",
                (resultSet, row) -> new Peer(resultSet.getLong("id"), resultSet.getString("name"),
                        resultSet.getString("address")),
                identity.nodeId());
    }

    /** One round with one computer. Returns the problem, or null when it went fine. */
    public synchronized String syncWith(Peer peer) {
        jdbc.update("""
                INSERT INTO sync_peer_state (node_id, last_attempt) VALUES (?, now())
                ON CONFLICT (node_id) DO UPDATE SET last_attempt = now()
                """, peer.id());
        Long cursor = jdbc.queryForObject("SELECT cursor FROM sync_peer_state WHERE node_id = ?", Long.class, peer.id());

        try {
            ChangesResponse response = client.changes(peer.address(), cursor == null ? 0 : cursor,
                    encryptor.syncToken(), identity.nodeId(), syncPort);
            if (response.node().id() != peer.id()) {
                throw new SyncException("A different computer (" + response.node().name()
                        + ") answers at " + peer.address() + ".");
            }
            SyncApplier.Result result = applier.apply(response.changes());
            jdbc.update("""
                    UPDATE sync_peer_state SET cursor = ?, last_success = now(), last_error = NULL WHERE node_id = ?
                    """, response.cursor(), peer.id());
            if (result.applied() > 0) {
                log.info("Synced from {}: {} changes applied, {} waiting", peer.name(), result.applied(), result.waiting());
            }
            return null;
        } catch (SyncException exception) {
            recordError(peer, exception.getMessage());
            return exception.getMessage();
        } catch (RuntimeException exception) {
            log.warn("Sync with {} failed", peer.name(), exception);
            recordError(peer, "Sync failed: " + exception.getMessage());
            return exception.getMessage();
        }
    }

    private void recordError(Peer peer, String message) {
        jdbc.update("UPDATE sync_peer_state SET last_error = ? WHERE node_id = ?", truncate(message, 500), peer.id());
    }

    public Status status() {
        if (!identity.isEnabled()) {
            return new Status(false, null, null, List.of(), 0);
        }
        Self self = identity.self().orElseThrow();
        List<PeerStatus> peers = jdbc.query("""
                SELECT n.id, n.name, n.address, s.last_success, s.last_attempt, s.last_error,
                       extract(epoch FROM now() - s.last_success)::bigint AS seconds_since_success
                FROM sync_node n LEFT JOIN sync_peer_state s ON s.node_id = n.id
                WHERE n.id <> ?
                ORDER BY n.id
                """, (resultSet, row) -> new PeerStatus(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("address"),
                instant(resultSet.getTimestamp("last_success")),
                instant(resultSet.getTimestamp("last_attempt")),
                resultSet.getString("last_error"),
                resultSet.getObject("seconds_since_success") == null ? null : resultSet.getLong("seconds_since_success")),
                self.nodeId());
        Integer waiting = jdbc.queryForObject("SELECT count(*) FROM sync_pending", Integer.class);
        return new Status(true, self.nodeId(), self.name(), peers, waiting == null ? 0 : waiting);
    }

    private Self requireSelf() {
        return identity.self().orElseThrow(() -> new IllegalStateException("This computer isn't set up for sync yet."));
    }

    static String address(String host, int port) {
        String cleanHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return cleanHost + ":" + port;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
