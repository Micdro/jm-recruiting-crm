package com.janemichael.jmrecruitingcrm.sync;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.sync.SyncCipher.UnreadableException;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ChangesResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ErrorResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.NodeInfo;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.PushRequest;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.PushResponse;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Talks to the other computers' sync ports over a direct connection. */
@Component
public class SyncClient {

    /** A problem reaching or talking to another computer, in words a person can act on. */
    public static class SyncException extends Exception {
        public SyncException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Everything the other computer has changed since the cursor (a position in its own records). */
    ChangesResponse changes(String address, long since, SyncCipher cipher, NodeInfo self) throws SyncException {
        return exchange(address, "GET", "/api/sync/changes?since=" + since, null, cipher, self.id(),
                ChangesResponse.class, Duration.ofMinutes(3));
    }

    /** Sends this computer's changes to the other one, for when it can't reach this one itself. */
    PushResponse push(String address, List<SyncChange> changes, SyncCipher cipher, NodeInfo self) throws SyncException {
        return exchange(address, "POST", "/api/sync/push", new PushRequest(SyncProtocol.VERSION, self, changes),
                cipher, self.id(), PushResponse.class, Duration.ofMinutes(3));
    }

    private <T> T exchange(String address, String method, String pathAndQuery, Object payload, SyncCipher cipher,
                           long selfNode, Class<T> type, Duration timeout) throws SyncException {
        long time = System.currentTimeMillis();
        byte[] body = payload == null ? new byte[0]
                : cipher.seal(SyncProtocol.requestName(method, pathAndQuery, selfNode, time), pack(payload));
        String signature = cipher.sign(SyncProtocol.signed(method, pathAndQuery, selfNode, time, body));

        URI uri;
        try {
            uri = URI.create("http://" + address + pathAndQuery);
        } catch (IllegalArgumentException invalid) {
            throw new SyncException("Not a usable address: " + address);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header(SyncProtocol.NODE_HEADER, String.valueOf(selfNode))
                .header(SyncProtocol.TIME_HEADER, String.valueOf(time))
                .header(SyncProtocol.SIGNATURE_HEADER, signature)
                .header("Content-Type", "application/octet-stream")
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException | ConnectException exception) {
            throw new SyncException("Can't reach " + address + ".");
        } catch (HttpTimeoutException exception) {
            throw new SyncException(address + " took too long to answer.");
        } catch (IOException exception) {
            throw new SyncException("Can't reach " + address + ": " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SyncException("Sync was interrupted.");
        }

        if (response.statusCode() == 401) {
            throw new SyncException("The computer at " + address + " uses a different CRM key, "
                    + "or its clock is more than ten minutes off.");
        }
        if (response.statusCode() != 200) {
            String message;
            try {
                message = json.readValue(response.body(), ErrorResponse.class).message();
            } catch (IOException unreadable) {
                message = "HTTP " + response.statusCode();
            }
            throw new SyncException(message);
        }
        try {
            return unpack(cipher.open(SyncProtocol.responseName(signature), response.body()), type);
        } catch (UnreadableException | IOException exception) {
            throw new SyncException("The computer at " + address + " sent something unreadable.");
        }
    }

    private byte[] pack(Object value) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(packed)) {
            json.writeValue(gzip, value);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare a sync request", exception);
        }
        return packed.toByteArray();
    }

    private <T> T unpack(byte[] packed, Class<T> type) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return json.readValue(in, type);
        }
    }

    /** "michael-pc", "http://100.101.102.103/" or "michael-pc:4781" all become host:port. */
    public static String normalizeAddress(String input, int defaultPort) {
        if (input == null) {
            return null;
        }
        String address = input.trim().replaceFirst("(?i)^https?://", "");
        int slash = address.indexOf('/');
        if (slash >= 0) {
            address = address.substring(0, slash);
        }
        if (address.isEmpty()) {
            return null;
        }
        boolean hasPort = address.matches(".*:\\d+$") && address.indexOf(':') == address.lastIndexOf(':');
        return hasPort ? address : address + ":" + defaultPort;
    }
}
