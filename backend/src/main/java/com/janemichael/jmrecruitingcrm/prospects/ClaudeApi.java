package com.janemichael.jmrecruitingcrm.prospects;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls the Claude API (api.anthropic.com) with the firm's own API key. */
@Component
public class ClaudeApi {

    static final String BASE_URL = "https://api.anthropic.com";
    static final String VERSION = "2023-06-01";

    /**
     * What the API answered.
     *
     * @param retryAfterSeconds the API's retry-after header, when it asked the CRM to wait
     */
    public record Reply(int status, String body, Long retryAfterSeconds) {

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Sends a Messages API request. A search-heavy turn can take several minutes. */
    public Reply messages(String apiKey, String body) throws IOException, InterruptedException {
        HttpRequest request = base(apiKey, "/v1/messages")
                .timeout(Duration.ofMinutes(10))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    /** Lists the models the key can use: a free call that shows whether the key works. */
    public Reply models(String apiKey) throws IOException, InterruptedException {
        return send(base(apiKey, "/v1/models?limit=1").timeout(Duration.ofSeconds(20)).GET().build());
    }

    private static HttpRequest.Builder base(String apiKey, String path) {
        return HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("x-api-key", apiKey)
                .header("anthropic-version", VERSION)
                .header("accept", "application/json")
                .header("user-agent", "JM-Recruiting-CRM (prospect finder)");
    }

    private Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        Long retryAfter = response.headers().firstValue("retry-after")
                .filter(value -> value.matches("\\d{1,5}"))
                .map(Long::parseLong)
                .orElse(null);
        return new Reply(response.statusCode(), response.body(), retryAfter);
    }
}
