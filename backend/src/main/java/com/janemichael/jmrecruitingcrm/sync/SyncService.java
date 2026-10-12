package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.sync.SyncClient.SyncException;
import com.janemichael.jmrecruitingcrm.sync.SyncIdentity.Self;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ChangesResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.NodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct connections, alongside the shared folder ({@link FolderSync}): every few seconds this
 * computer fetches what changed on each computer it can reach, at the addresses that computer left
 * in the folder. If that computer can't reach this one in turn, this one also sends it its own
 * changes, so only one of the two needs to accept connections. A computer that can't be reached
 * is tried less often, and the folder carries the changes meanwhile. Also reports sync status.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration LONGEST_RETRY = Duration.ofMinutes(10);

    public record Peer(long id, String name, List<String> addresses) {
    }

    public record PeerStatus(
            long id,
            String name,
            Instant lastSuccess,
            Instant lastAttempt,
            String lastError,
            /** Measured by this computer's clock, so the page needn't compare clocks. */
            Long secondsSinceSuccess,
            /** Since that computer last noted in the sync folder that it was running (its clock). */
            Long secondsSinceSeen,
            /** Since the last direct connection with it worked, or null if none has. */
            Long secondsSinceDirect,
            String directAddress
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
    private final long interval;

    /** When to try a computer again after it couldn't be reached, and how many times in a row it failed. */
    private final Map<Long, Instant> retryAt = new ConcurrentHashMap<>();
    private final Map<Long, Integer> failuresInARow = new ConcurrentHashMap<>();

    public SyncService(
            JdbcTemplate jdbc,
            SyncIdentity identity,
            SyncFeed feed,
            SyncApplier applier,
            SyncClient client,
            FieldEncryptor encryptor,
            @Value("${app.sync.interval-ms:5000}") long interval
    ) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.feed = feed;
        this.applier = applier;
        this.client = client;
        this.encryptor = encryptor;
        this.interval = interval;
    }

    // Answering other computers -------------------------------------------------

    /** Everything changed since the cursor, except what the asking computer made itself. */
    public ChangesResponse changesSince(long since, long callerNode) {
        notePulledBy(callerNode);
        SyncFeed.Feed changes = feed.since(since);
        List<SyncChange> forCaller = changes.changes().stream()
                .filter(change -> change.origin() != callerNode)
                .toList();
        return new ChangesResponse(SyncProtocol.VERSION, self(), changes.cursor(), forCaller);
    }

    NodeInfo self() {
        Self self = identity.self().orElseThrow(() -> new IllegalStateException("This computer isn't set up for sync yet."));
        return new NodeInfo(self.nodeId(), self.name());
    }

    /** That computer fetches from this one by itself, so this one needn't send it changes. */
    private void notePulledBy(long node) {
        jdbc.update("""
                INSERT INTO sync_peer_state (node_id, pulled_by_at) VALUES (?, now())
                ON CONFLICT (node_id) DO UPDATE SET pulled_by_at = now()
                """, node);
    }

    // Asking other computers -----------------------------------------------------

    @Scheduled(initialDelayString = "${app.sync.initial-delay-ms:3000}", fixedDelayString = "${app.sync.interval-ms:5000}")
    public void syncAll() {
        // Runs while the CRM is locked too: it copies scrambled data without reading it.
        if (!identity.isEnabled() || !encryptor.hasSyncToken()) {
            return;
        }
        Instant now = Instant.now();
        for (Peer peer : peers()) {
            Instant next = retryAt.get(peer.id());
            if (next == null || !now.isBefore(next)) {
                syncWith(peer);
            }
        }
    }

    /** Computers that left addresses in the folder (or, from older versions, have one on record). */
    public List<Peer> peers() {
        return jdbc.query("""
                SELECT n.id, n.name, n.address, s.direct_address, s.direct_addresses
                FROM sync_node n LEFT JOIN sync_peer_state s ON s.node_id = n.id
                WHERE n.id <> ? AND (s.direct_addresses IS NOT NULL OR n.address IS NOT NULL)
                ORDER BY n.id
                """, (resultSet, row) -> {
            // The address that worked last time first, then the rest as that computer listed them.
            Set<String> addresses = new LinkedHashSet<>();
            add(addresses, resultSet.getString("direct_address"));
            String listed = resultSet.getString("direct_addresses");
            if (listed != null) {
                for (String address : listed.split(",")) {
                    add(addresses, address);
                }
            }
            add(addresses, resultSet.getString("address"));
            return new Peer(resultSet.getLong("id"), resultSet.getString("name"), List.copyOf(addresses));
        }, identity.nodeId());
    }

    /** One round with one computer: fetch its changes, and send ours if it can't fetch them. */
    public synchronized String syncWith(Peer peer) {
        jdbc.update("""
                INSERT INTO sync_peer_state (node_id, last_attempt) VALUES (?, now())
                ON CONFLICT (node_id) DO UPDATE SET last_attempt = now()
                """, peer.id());
        Map<String, Object> state = jdbc.queryForMap(
                "SELECT cursor, pulled_by_at FROM sync_peer_state WHERE node_id = ?", peer.id());
        long cursor = state.get("cursor") == null ? 0 : ((Number) state.get("cursor")).longValue();
        SyncCipher cipher = SyncCipher.forDirect(encryptor.syncToken());
        NodeInfo self = self();

        String problem = "No address to reach it at.";
        for (String address : peer.addresses()) {
            try {
                ChangesResponse response = client.changes(address, cursor, cipher, self);
                if (response.node().id() != peer.id()) {
                    problem = "A different computer (" + response.node().name() + ") answers at " + address + ".";
                    continue;
                }
                SyncApplier.Result result = applier.apply(response.changes());
                jdbc.update("""
                        UPDATE sync_peer_state SET cursor = ?, direct_address = ?, direct_last_success = now(),
                               direct_last_error = NULL
                        WHERE node_id = ?
                        """, response.cursor(), address, peer.id());
                if (result.applied() > 0) {
                    log.info("Synced directly from {}: {} changes applied, {} waiting",
                            peer.name(), result.applied(), result.waiting());
                }
                if (!fetchesFromUs((Timestamp) state.get("pulled_by_at"))) {
                    push(peer, address, cipher, self);
                }
                retryAt.remove(peer.id());
                failuresInARow.remove(peer.id());
                return null;
            } catch (SyncException exception) {
                problem = exception.getMessage();
            } catch (RuntimeException exception) {
                log.warn("Direct sync with {} failed", peer.name(), exception);
                problem = "Sync failed: " + exception.getMessage();
            }
        }

        jdbc.update("UPDATE sync_peer_state SET direct_last_error = ? WHERE node_id = ?", truncate(problem, 500), peer.id());
        int failures = failuresInARow.merge(peer.id(), 1, Integer::sum);
        Duration wait = FIRST_RETRY.multipliedBy(1L << Math.min(failures - 1, 5));
        retryAt.put(peer.id(), Instant.now().plus(wait.compareTo(LONGEST_RETRY) > 0 ? LONGEST_RETRY : wait));
        return problem;
    }

    /** Whether that computer has fetched from this one recently, so it gets our changes anyway. */
    private boolean fetchesFromUs(Timestamp pulledByAt) {
        return pulledByAt != null
                && pulledByAt.toInstant().isAfter(Instant.now().minusMillis(Math.max(15_000, interval * 4)));
    }

    /** Sends this computer's changes that the other one hasn't had yet. */
    private void push(Peer peer, String address, SyncCipher cipher, NodeInfo self) throws SyncException {
        // To start with, only what came after the last changes this computer left in the folder:
        // the other computer gets those from the folder.
        jdbc.update("""
                UPDATE sync_peer_state SET push_cursor = (SELECT folder_cursor FROM sync_self LIMIT 1)
                WHERE node_id = ? AND push_cursor IS NULL
                """, peer.id());
        Long pushCursor = jdbc.queryForObject("SELECT push_cursor FROM sync_peer_state WHERE node_id = ?",
                Long.class, peer.id());
        SyncFeed.Feed changes = feed.since(pushCursor == null ? 0 : pushCursor);
        List<SyncChange> theirs = new ArrayList<>();
        for (SyncChange change : changes.changes()) {
            // What that computer made itself, it already has.
            if (change.origin() != peer.id()) {
                theirs.add(change);
            }
        }
        for (int start = 0; start < theirs.size(); start += SyncProtocol.CHANGES_PER_PUSH) {
            client.push(address, theirs.subList(start, Math.min(theirs.size(), start + SyncProtocol.CHANGES_PER_PUSH)),
                    cipher, self);
        }
        jdbc.update("UPDATE sync_peer_state SET push_cursor = ? WHERE node_id = ?", changes.cursor(), peer.id());
    }

    public Status status() {
        if (!identity.isEnabled()) {
            return new Status(false, null, null, List.of(), 0);
        }
        Self self = identity.self().orElseThrow();
        List<PeerStatus> peers = jdbc.query("""
                SELECT n.id, n.name, s.last_success, s.last_attempt, s.last_error, s.direct_address,
                       extract(epoch FROM now() - s.last_success)::bigint AS seconds_since_success,
                       greatest(0, extract(epoch FROM now() - s.last_seen))::bigint AS seconds_since_seen,
                       extract(epoch FROM now() - s.direct_last_success)::bigint AS seconds_since_direct
                FROM sync_node n LEFT JOIN sync_peer_state s ON s.node_id = n.id
                WHERE n.id <> ?
                ORDER BY n.id
                """, (resultSet, row) -> new PeerStatus(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                instant(resultSet.getTimestamp("last_success")),
                instant(resultSet.getTimestamp("last_attempt")),
                resultSet.getString("last_error"),
                nullableLong(resultSet.getObject("seconds_since_success")),
                nullableLong(resultSet.getObject("seconds_since_seen")),
                nullableLong(resultSet.getObject("seconds_since_direct")),
                resultSet.getString("direct_address")),
                self.nodeId());
        Integer waiting = jdbc.queryForObject("SELECT count(*) FROM sync_pending", Integer.class);
        return new Status(true, self.nodeId(), self.name(), peers, waiting == null ? 0 : waiting);
    }

    /** Forgets a computer's backoff, for "Sync now". */
    public void retryNow() {
        retryAt.clear();
    }

    private static void add(Set<String> addresses, String address) {
        if (address != null && !address.isBlank()) {
            addresses.add(address.trim());
        }
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
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
