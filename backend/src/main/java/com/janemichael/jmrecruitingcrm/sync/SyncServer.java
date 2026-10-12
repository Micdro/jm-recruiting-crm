package com.janemichael.jmrecruitingcrm.sync;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.sync.SyncCipher.UnreadableException;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ErrorResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.NodeInfo;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.PushRequest;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.PushResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The sync port, for direct connections: a small HTTP server, separate from the CRM itself (which
 * only answers on this computer), that answers nothing but the other computers in the group. It
 * runs only while direct connections are turned on ({@link DirectSync}).
 *
 * <p>It can be reachable from the internet, so it trusts nothing: every request must be signed
 * with the group's key and be recent, bodies are sealed both ways, and an address that keeps
 * failing is shut out for a while.
 */
@Component
public class SyncServer {

    private static final Logger log = LoggerFactory.getLogger(SyncServer.class);

    /** Failed attempts from one address within {@link #FAILURE_WINDOW_MS} before it is shut out. */
    private static final int MAX_FAILURES = 20;
    private static final long FAILURE_WINDOW_MS = 10 * 60 * 1000L;

    private final SyncService sync;
    private final SyncApplier applier;
    private final SyncIdentity identity;
    private final FieldEncryptor encryptor;
    private final boolean desktop;
    private final int port;
    private final String bindAddress;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, Deque<Long>> failures = new HashMap<>();

    private HttpServer server;
    private ExecutorService executor;

    public SyncServer(
            SyncService sync,
            SyncApplier applier,
            SyncIdentity identity,
            FieldEncryptor encryptor,
            @Value("${app.desktop.enabled:false}") boolean desktop,
            @Value("${app.sync.port:4781}") int port,
            @Value("${app.sync.bind-address:0.0.0.0}") String bindAddress
    ) {
        this.sync = sync;
        this.applier = applier;
        this.identity = identity;
        this.encryptor = encryptor;
        this.desktop = desktop;
        this.port = port;
        this.bindAddress = bindAddress;
    }

    /** Starts listening. Returns false if the port couldn't be opened. */
    public synchronized boolean open() {
        if (server != null) {
            return true;
        }
        if (!desktop || port < 0) {
            return false;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException exception) {
            log.error("Could not open the sync port {}", port, exception);
            return false;
        }
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "sync-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/api/sync/", this::handle);
        server.start();
        log.info("Sync port open on {}:{}", bindAddress, port());
        return true;
    }

    public synchronized void close() {
        if (server != null) {
            server.stop(1);
            executor.shutdownNow();
            server = null;
            log.info("Sync port closed");
        }
    }

    public synchronized boolean isOpen() {
        return server != null;
    }

    /** The port it listens on (the configured one, or the one picked when that is 0). */
    public synchronized int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
        try {
            if (shutOut(remote)) {
                sendError(exchange, 429, "Too many failed attempts. Try again later.");
                return;
            }
            if (!encryptor.hasSyncToken() || !identity.isEnabled()) {
                sendError(exchange, 404, "The CRM on that computer isn't set up yet.");
                return;
            }

            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            String pathAndQuery = query == null ? path : path + "?" + query;
            Long node = parseLong(exchange.getRequestHeaders().getFirst(SyncProtocol.NODE_HEADER));
            Long time = parseLong(exchange.getRequestHeaders().getFirst(SyncProtocol.TIME_HEADER));
            String signature = exchange.getRequestHeaders().getFirst(SyncProtocol.SIGNATURE_HEADER);
            // The cheap checks first, so a stranger can't make this computer read a large body.
            if (node == null || time == null || signature == null
                    || Math.abs(System.currentTimeMillis() - time) > SyncProtocol.MAX_CLOCK_SKEW_MS) {
                recordFailure(remote);
                sendError(exchange, 401, "Not signed with this CRM's key.");
                return;
            }
            Long declared = parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));
            byte[] body = "POST".equals(method) && (declared == null || declared <= SyncProtocol.MAX_BODY_BYTES)
                    ? readBody(exchange)
                    : "POST".equals(method) ? null : new byte[0];
            if (body == null) {
                recordFailure(remote);
                sendError(exchange, 413, "Too much at once.");
                return;
            }

            SyncCipher cipher = SyncCipher.forDirect(encryptor.syncToken());
            if (!cipher.verify(SyncProtocol.signed(method, pathAndQuery, node, time, body), signature)) {
                recordFailure(remote);
                sendError(exchange, 401, "Not signed with this CRM's key.");
                return;
            }

            Object answer;
            if ("GET".equals(method) && "/api/sync/changes".equals(path)) {
                answer = sync.changesSince(longParam(query, "since"), node);
            } else if ("POST".equals(method) && "/api/sync/push".equals(path)) {
                PushRequest push = unpack(cipher.open(SyncProtocol.requestName(method, pathAndQuery, node, time), body),
                        PushRequest.class);
                int applied = push.changes() == null ? 0 : applier.apply(push.changes()).applied();
                if (applied > 0) {
                    log.info("Received {} changes directly from computer {}", applied, node);
                }
                NodeInfo self = sync.self();
                answer = new PushResponse(SyncProtocol.VERSION, self, applied);
            } else {
                sendError(exchange, 404, "Not found.");
                return;
            }
            sendSealed(exchange, cipher, signature, answer);
        } catch (UnreadableException exception) {
            recordFailure(remote);
            sendError(exchange, 400, "The request couldn't be read.");
        } catch (RuntimeException exception) {
            log.warn("Sync request failed", exception);
            sendError(exchange, 500, "The other computer hit an error.");
        } finally {
            exchange.close();
        }
    }

    // Shutting out addresses that keep failing ---------------------------------------

    private synchronized boolean shutOut(String remote) {
        Deque<Long> times = failures.get(remote);
        if (times == null) {
            return false;
        }
        long cutoff = System.currentTimeMillis() - FAILURE_WINDOW_MS;
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.pollFirst();
        }
        if (times.isEmpty()) {
            failures.remove(remote);
            return false;
        }
        return times.size() >= MAX_FAILURES;
    }

    private synchronized void recordFailure(String remote) {
        if (failures.size() > 10_000) {
            failures.clear();
        }
        failures.computeIfAbsent(remote, key -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
        log.debug("Refused a sync request from {}", remote);
    }

    // Reading and writing --------------------------------------------------------------

    /** The request body, or null if it is too large. */
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(SyncProtocol.MAX_BODY_BYTES + 1);
            return body.length > SyncProtocol.MAX_BODY_BYTES ? null : body;
        }
    }

    private <T> T unpack(byte[] packed, Class<T> type) throws UnreadableException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return json.readValue(in, type);
        } catch (IOException exception) {
            throw new UnreadableException("The request couldn't be read");
        }
    }

    private void sendSealed(HttpExchange exchange, SyncCipher cipher, String signature, Object body) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(packed)) {
            json.writeValue(gzip, body);
        }
        byte[] sealed = cipher.seal(SyncProtocol.responseName(signature), packed.toByteArray());
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, sealed.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(sealed);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = json.writeValueAsBytes(new ErrorResponse(message));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static long longParam(String query, String name) {
        if (query == null) {
            return 0;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                Long value = parseLong(URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                return value == null ? 0 : value;
            }
        }
        return 0;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
