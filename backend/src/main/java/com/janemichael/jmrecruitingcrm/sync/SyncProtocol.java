package com.janemichael.jmrecruitingcrm.sync;

import java.util.List;

/** The messages computers exchange on the sync port. */
public final class SyncProtocol {

    public static final int VERSION = 1;
    public static final String TOKEN_HEADER = "Authorization";
    public static final String NODE_HEADER = "X-Sync-Node";
    public static final String PORT_HEADER = "X-Sync-Port";

    private SyncProtocol() {
    }

    public record NodeInfo(long id, String name) {
    }

    public record JoinRequest(String name, int port) {
    }

    public record JoinResponse(int protocol, long nodeId, NodeInfo node) {
    }

    public record ChangesResponse(int protocol, NodeInfo node, long cursor, List<SyncChange> changes) {
    }

    public record ErrorResponse(String message) {
    }
}
