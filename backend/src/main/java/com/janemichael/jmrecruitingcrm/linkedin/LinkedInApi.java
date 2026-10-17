package com.janemichael.jmrecruitingcrm.linkedin;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calls LinkedIn with the firm's own LinkedIn app: signing a person in (OAuth 2.0), and posting to
 * their profile with the self-serve Share on LinkedIn product (ugcPosts, with pictures uploaded
 * through the assets API).
 */
@Component
public class LinkedInApi {

    static final String AUTHORIZE_URL = "https://www.linkedin.com/oauth/v2/authorization";
    static final String TOKEN_URL = "https://www.linkedin.com/oauth/v2/accessToken";
    static final String API_URL = "https://api.linkedin.com";
    /** Sign in with OpenID Connect (for the person's id and name) and Share on LinkedIn. */
    static final String SCOPES = "openid profile w_member_social";

    /**
     * What LinkedIn answered.
     *
     * @param restliId the x-restli-id header: the new post's id, when one was made
     */
    public record Reply(int status, String body, String restliId) {

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Where to send the person to approve the firm's app. */
    public static String authorizeUrl(String clientId, String redirectUri, String state) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("state", state);
        query.put("scope", SCOPES);
        return AUTHORIZE_URL + "?" + form(query);
    }

    /** Swaps the code LinkedIn sent back for an access token. */
    public Reply token(String clientId, String clientSecret, String code, String redirectUri)
            throws IOException, InterruptedException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("redirect_uri", redirectUri);
        return send(HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/x-www-form-urlencoded")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                .build());
    }

    /** The signed-in person: their id ({@code sub}) and name. */
    public Reply userInfo(String token) throws IOException, InterruptedException {
        return send(api(token, "/v2/userinfo").timeout(Duration.ofSeconds(30)).GET().build());
    }

    /** Asks LinkedIn where to upload a picture for a post. */
    public Reply registerUpload(String token, String json) throws IOException, InterruptedException {
        return send(api(token, "/v2/assets?action=registerUpload")
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("X-Restli-Protocol-Version", "2.0.0")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    /** Uploads the picture to the address LinkedIn gave. */
    public Reply upload(String token, String uploadUrl, byte[] content) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(Duration.ofMinutes(2))
                .header("authorization", "Bearer " + token)
                .header("content-type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build());
    }

    /** Publishes a post. */
    public Reply share(String token, String json) throws IOException, InterruptedException {
        return send(api(token, "/v2/ugcPosts")
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .header("X-Restli-Protocol-Version", "2.0.0")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private static HttpRequest.Builder api(String token, String path) {
        return HttpRequest.newBuilder(URI.create(API_URL + path))
                .header("authorization", "Bearer " + token)
                .header("accept", "application/json")
                .header("user-agent", "JM-Recruiting-CRM");
    }

    private Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body(), response.headers().firstValue("x-restli-id").orElse(null));
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                // Spaces as %20, which every reader takes as a space (some read + literally).
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("&"));
    }
}
