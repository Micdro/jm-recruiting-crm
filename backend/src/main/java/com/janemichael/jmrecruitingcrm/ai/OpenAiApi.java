package com.janemichael.jmrecruitingcrm.ai;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls the OpenAI API (api.openai.com) with the firm's own API key. */
@Component
public class OpenAiApi {

    static final String BASE_URL = "https://api.openai.com";

    /** What the API answered. */
    public record Reply(int status, String body) {

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Sends a Responses API request. Models that think first can take a minute or two. */
    public Reply responses(String apiKey, String body) throws IOException, InterruptedException {
        HttpRequest request = base(apiKey, "/v1/responses")
                .timeout(Duration.ofMinutes(5))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    /** Sends an Image API request. A picture can take a minute. */
    public Reply images(String apiKey, String body) throws IOException, InterruptedException {
        HttpRequest request = base(apiKey, "/v1/images/generations")
                .timeout(Duration.ofMinutes(5))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    /** Lists the models the key can use: a free call that shows whether the key works. */
    public Reply models(String apiKey) throws IOException, InterruptedException {
        return send(base(apiKey, "/v1/models").timeout(Duration.ofSeconds(20)).GET().build());
    }

    private static HttpRequest.Builder base(String apiKey, String path) {
        return HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("authorization", "Bearer " + apiKey)
                .header("accept", "application/json")
                .header("user-agent", "JM-Recruiting-CRM");
    }

    private Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }
}
