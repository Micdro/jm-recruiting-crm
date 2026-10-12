package com.janemichael.jmrecruitingcrm.sync;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ChangesResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ErrorResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/** Talks to the other computers' sync ports. */
@Component
public class SyncClient {

    /** A problem reaching or talking to another computer, in words a person can act on. */
    public static class SyncException extends Exception {
        public SyncException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ChangesResponse changes(String address, long since, String token, int selfNode, int selfPort)
            throws SyncException {
        HttpRequest request = base(address, "/api/sync/changes?since=" + since, token, selfNode, selfPort)
                .timeout(Duration.ofMinutes(3))
                .GET()
                .build();
        return send(request, ChangesResponse.class);
    }

    private HttpRequest.Builder base(String address, String path, String token, int selfNode, int selfPort) {
        return HttpRequest.newBuilder(URI.create("http://" + address + path))
                .header(SyncProtocol.TOKEN_HEADER, "Bearer " + token)
                .header(SyncProtocol.NODE_HEADER, String.valueOf(selfNode))
                .header(SyncProtocol.PORT_HEADER, String.valueOf(selfPort))
                .header("Accept-Encoding", "gzip");
    }

    private <T> T send(HttpRequest request, Class<T> type) throws SyncException {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException | ConnectException exception) {
            throw new SyncException("Can't reach " + request.uri().getAuthority()
                    + ". Check that the computer is on and the CRM is running there.");
        } catch (HttpTimeoutException exception) {
            throw new SyncException(request.uri().getAuthority() + " took too long to answer.");
        } catch (IOException exception) {
            throw new SyncException("Can't reach " + request.uri().getAuthority() + ": " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SyncException("Sync was interrupted.");
        }

        try (InputStream raw = response.body();
             InputStream body = "gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))
                     ? new GZIPInputStream(raw) : raw) {
            if (response.statusCode() == 401) {
                throw new SyncException("The other computer uses a different CRM key.");
            }
            if (response.statusCode() != 200) {
                String message;
                try {
                    message = json.readValue(body, ErrorResponse.class).message();
                } catch (IOException unreadable) {
                    message = "HTTP " + response.statusCode();
                }
                throw new SyncException(message);
            }
            return json.readValue(body, type);
        } catch (IOException exception) {
            throw new SyncException("The other computer sent something unreadable: " + exception.getMessage());
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
