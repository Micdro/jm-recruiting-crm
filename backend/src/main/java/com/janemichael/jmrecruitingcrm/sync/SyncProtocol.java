package com.janemichael.jmrecruitingcrm.sync;

import java.util.List;

/**
 * What computers send each other over a direct connection.
 *
 * <p>Every request is signed with a key derived from the sync token (the token itself is never
 * sent), and carries the time, so an old request can't be replayed later. Bodies both ways are
 * gzipped JSON sealed with AES-256-GCM; a response is sealed to the request it answers. Errors are
 * plain JSON and never carry data.
 */
public final class SyncProtocol {

    public static final int VERSION = 2;
    public static final String NODE_HEADER = "X-Sync-Node";
    public static final String TIME_HEADER = "X-Sync-Time";
    public static final String SIGNATURE_HEADER = "X-Sync-Signature";

    /** How far apart two computers' clocks may be before their requests are refused. */
    public static final long MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L;
    /** The largest request body accepted (a push of many changes, compressed). */
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    /** The most changes sent in one push. */
    public static final int CHANGES_PER_PUSH = 2000;

    private SyncProtocol() {
    }

    public record NodeInfo(long id, String name) {
    }

    public record ChangesResponse(int protocol, NodeInfo node, long cursor, List<SyncChange> changes) {
    }

    public record PushRequest(int protocol, NodeInfo node, List<SyncChange> changes) {
    }

    public record PushResponse(int protocol, NodeInfo node, int applied) {
    }

    public record ErrorResponse(String message) {
    }

    /** What a request's signature covers: everything about it, including its (sealed) body. */
    static String signed(String method, String pathAndQuery, long node, long time, byte[] body) {
        return method + "\n" + pathAndQuery + "\n" + node + "\n" + time + "\n" + SyncCipher.digest(body);
    }

    /** The name a request body is sealed under. */
    static String requestName(String method, String pathAndQuery, long node, long time) {
        return "request\n" + method + "\n" + pathAndQuery + "\n" + node + "\n" + time;
    }

    /** The name a response is sealed under: the request it answers. */
    static String responseName(String requestSignature) {
        return "response\n" + requestSignature;
    }
}
