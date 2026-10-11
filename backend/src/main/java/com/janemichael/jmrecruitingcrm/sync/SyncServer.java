package com.janemichael.jmrecruitingcrm.sync;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ErrorResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.JoinRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * The sync port: a small, separate HTTP server that only answers other computers in the group.
 * It runs on desktop copies only. The CRM itself is served on localhost; this port carries nothing
 * but sync, and every request must carry the token derived from the shared CRM key.
 */
@Component
public class SyncServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SyncServer.class);

    private final SyncService sync;
    private final SyncIdentity identity;
    private final FieldEncryptor encryptor;
    private final boolean desktop;
    private final int port;
    private final String bindAddress;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean running;

    public SyncServer(
            SyncService sync,
            SyncIdentity identity,
            FieldEncryptor encryptor,
            @Value("${app.desktop.enabled:false}") boolean desktop,
            @Value("${app.sync.port:4781}") int port,
            @Value("${app.sync.bind-address:0.0.0.0}") String bindAddress
    ) {
        this.sync = sync;
        this.identity = identity;
        this.encryptor = encryptor;
        this.desktop = desktop;
        this.port = port;
        this.bindAddress = bindAddress;
    }

    @Override
    public void start() {
        if (!desktop || port <= 0) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException exception) {
            log.error("Could not open the sync port {}. Other computers won't be able to sync with this one.", port, exception);
            return;
        }
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "sync-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/api/sync/", this::handle);
        server.start();
        running = true;
        log.info("Sync port open on {}:{}", bindAddress, port);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(1);
            executor.shutdownNow();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!encryptor.isConfigured() || !identity.isEnabled()) {
                send(exchange, 404, new ErrorResponse("The CRM on that computer isn't set up yet."));
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst(SyncProtocol.TOKEN_HEADER))) {
                send(exchange, 401, new ErrorResponse("Wrong CRM key."));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String remoteHost = exchange.getRemoteAddress().getAddress().getHostAddress();

            if ("GET".equals(method) && "/api/sync/changes".equals(path)) {
                long since = longParam(exchange.getRequestURI().getRawQuery(), "since");
                Long caller = parseLong(exchange.getRequestHeaders().getFirst(SyncProtocol.NODE_HEADER));
                Long callerPort = parseLong(exchange.getRequestHeaders().getFirst(SyncProtocol.PORT_HEADER));
                send(exchange, 200, sync.changesSince(since, caller, remoteHost,
                        callerPort == null ? null : callerPort.intValue()));
            } else if ("POST".equals(method) && "/api/sync/join".equals(path)) {
                JoinRequest request = json.readValue(exchange.getRequestBody(), JoinRequest.class);
                send(exchange, 200, sync.acceptJoin(request.name(), remoteHost, request.port()));
            } else {
                send(exchange, 404, new ErrorResponse("Not found."));
            }
        } catch (RuntimeException exception) {
            log.warn("Sync request failed", exception);
            send(exchange, 500, new ErrorResponse("The other computer hit an error: " + exception.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] given = header.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = encryptor.syncToken().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, expected);
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        boolean gzip = bytes.length > 1024 && String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding"))
                .contains("gzip");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (gzip) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream out = new GZIPOutputStream(exchange.getResponseBody())) {
                out.write(bytes);
            }
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
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
