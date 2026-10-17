package com.janemichael.jmrecruitingcrm.linkedin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.Text;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posting to LinkedIn. The account owner adds the firm's LinkedIn app (its client id and secret)
 * once; each person then connects their own LinkedIn on their computer, and the CRM posts to their
 * profile for them. The app is shared and synced like the other settings; each connection stays on
 * the computer it was made on. LinkedIn connections last about 60 days, then the person connects
 * again.
 */
@Service
public class LinkedInService {

    public static final String CALLBACK = "/api/linkedin/callback";
    static final String CLIENT_ID = "linkedin.client_id";
    static final String CLIENT_SECRET = "linkedin.client_secret";
    /** How long someone has to approve the app on LinkedIn. */
    static final Duration SIGN_IN_TIME = Duration.ofMinutes(15);
    /** Connections that run out within this are shown as running out soon. */
    static final Duration EXPIRING = Duration.ofDays(7);

    /** A sign-in under way: who started it, from where, and when. */
    private record Pending(long userId, String origin, Instant started) {
    }

    /** @param expiring whether it runs out within a week */
    public record Connection(String name, Instant expiresAt, boolean expired, boolean expiring) {
    }

    /**
     * @param redirectUrl what to add as an authorized redirect URL in the LinkedIn app, for this computer
     * @param canManage   whether the signed-in person may change the app
     * @param me          the signed-in person's connection on this computer, or null
     */
    public record Status(boolean appAdded, String clientIdHint, String redirectUrl, boolean canManage, String owner,
                         Connection me) {
    }

    /** A post's words and picture, to publish. */
    public record Post(String text, byte[] picture) {
    }

    private final LinkedInApi api;
    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final CurrentUserService currentUser;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public LinkedInService(LinkedInApi api, JdbcTemplate jdbc, FieldEncryptor encryptor, CurrentUserService currentUser,
                           Clock clock) {
        this.api = api;
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // The firm's LinkedIn app: the account owner's -----------------------------------------

    public Status status(String origin) {
        AppUser me = currentUser.get();
        Map<String, String> app = app();
        String clientId = app.get(CLIENT_ID);
        return new Status(clientId != null && app.get(CLIENT_SECRET) != null,
                clientId == null || clientId.length() < 4 ? null : clientId.substring(clientId.length() - 4),
                origin + CALLBACK, currentUser.isAccountOwnerOrStandIn(me), currentUser.accountOwnerName(),
                connection(me.getId()));
    }

    public Status saveApp(String clientId, String clientSecret, String origin) {
        currentUser.requireAccountOwner("change the LinkedIn app");
        String id = Text.clean(clientId);
        String secret = Text.clean(clientSecret);
        if (id == null || secret == null || id.contains(" ") || secret.contains(" ")) {
            throw new BadRequestException("Paste the app's Client ID and Primary Client Secret from its Auth tab.");
        }
        write(CLIENT_ID, id);
        write(CLIENT_SECRET, secret);
        return status(origin);
    }

    public Status removeApp(String origin) {
        currentUser.requireAccountOwner("remove the LinkedIn app");
        write(CLIENT_ID, null);
        write(CLIENT_SECRET, null);
        return status(origin);
    }

    // Each person's connection -------------------------------------------------------------

    /** Where to send the signed-in person to connect their LinkedIn. */
    public String connectUrl(String origin) {
        AppUser me = currentUser.get();
        Map<String, String> app = app();
        if (app.get(CLIENT_ID) == null || app.get(CLIENT_SECRET) == null) {
            String owner = currentUser.accountOwnerName();
            throw new BadRequestException("Connecting needs the firm's LinkedIn app. " + (owner == null ? "An admin" : owner)
                    + " can add it under Plan on Marketing.");
        }
        Instant now = clock.instant();
        pending.values().removeIf(sign -> sign.started().plus(SIGN_IN_TIME).isBefore(now));
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.put(state, new Pending(me.getId(), origin, now));
        return LinkedInApi.authorizeUrl(app.get(CLIENT_ID), origin + CALLBACK, state);
    }

    /**
     * Finishes connecting when LinkedIn sends the person back, and says where to send them next:
     * the Marketing plan, with how it went.
     */
    public String callback(String code, String state, String error) {
        Pending sign = state == null ? null : pending.remove(state);
        AppUser me = currentUser.get();
        if (sign == null || sign.userId() != me.getId() || sign.started().plus(SIGN_IN_TIME).isBefore(clock.instant())) {
            return back("failed", "The LinkedIn sign-in had expired. Connect again.");
        }
        if (error != null) {
            return back("failed", error.startsWith("user_cancelled")
                    ? "You cancelled on LinkedIn, so nothing was connected."
                    : "LinkedIn didn't connect (" + error + ").");
        }
        if (code == null || code.isBlank()) {
            return back("failed", "LinkedIn didn't send a sign-in code. Connect again.");
        }
        Map<String, String> app = app();
        try {
            LinkedInApi.Reply token = api.token(app.get(CLIENT_ID), app.get(CLIENT_SECRET), code, sign.origin() + CALLBACK);
            if (!token.ok()) {
                return back("failed", "LinkedIn turned down the sign-in (" + token.status() + "). " + apiMessage(token.body())
                        + " Check the app's Client ID, secret and redirect URL.");
            }
            JsonNode granted = json.readTree(token.body());
            String accessToken = granted.path("access_token").asText("");
            long seconds = granted.path("expires_in").asLong(0);
            if (accessToken.isEmpty()) {
                return back("failed", "LinkedIn didn't send an access token. Connect again.");
            }
            LinkedInApi.Reply info = api.userInfo(accessToken);
            if (!info.ok()) {
                return back("failed", "LinkedIn didn't share who you are (" + info.status() + "). Check that the app has "
                        + "Sign In with LinkedIn using OpenID Connect added.");
            }
            JsonNode person = json.readTree(info.body());
            String id = person.path("sub").asText("");
            if (id.isEmpty()) {
                return back("failed", "LinkedIn didn't share who you are. Connect again.");
            }
            String name = Text.clean(person.path("name").asText(""));
            Instant expires = clock.instant().plusSeconds(seconds > 0 ? seconds : Duration.ofDays(60).toSeconds());
            jdbc.update("""
                    INSERT INTO linkedin_account (user_id, person_urn, name, access_token, expires_at, connected_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT (user_id) DO UPDATE SET person_urn = EXCLUDED.person_urn, name = EXCLUDED.name,
                        access_token = EXCLUDED.access_token, expires_at = EXCLUDED.expires_at, connected_at = now()
                    """, me.getId(), "urn:li:person:" + id, name == null ? null : encryptor.encrypt(name),
                    encryptor.encrypt(accessToken), Timestamp.from(expires));
            return back("connected", null);
        } catch (IOException unreachable) {
            return back("failed", "Couldn't reach LinkedIn. Check the internet connection and connect again.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return back("failed", "Connecting was interrupted. Connect again.");
        }
    }

    public void disconnect() {
        jdbc.update("DELETE FROM linkedin_account WHERE user_id = ?", currentUser.get().getId());
    }

    // Posting --------------------------------------------------------------------------------

    /**
     * Posts to the person's own LinkedIn profile, with the picture if there is one, and gives back
     * the post's address.
     */
    public String publish(AppUser person, Post post) {
        Map<String, Object> account = account(person.getId());
        if (account == null) {
            throw new BadRequestException("Connect your LinkedIn first, under Plan on Marketing.");
        }
        if (instant(account.get("expires_at")).isBefore(clock.instant())) {
            throw new BadRequestException("Your LinkedIn connection has run out. Connect again under Plan on Marketing.");
        }
        String token = encryptor.decrypt((String) account.get("access_token"));
        String author = (String) account.get("person_urn");
        try {
            String asset = post.picture() == null ? null : upload(token, author, post.picture());
            ObjectNode body = json.createObjectNode();
            body.put("author", author);
            body.put("lifecycleState", "PUBLISHED");
            ObjectNode share = body.putObject("specificContent").putObject("com.linkedin.ugc.ShareContent");
            share.putObject("shareCommentary").put("text", post.text());
            share.put("shareMediaCategory", asset == null ? "NONE" : "IMAGE");
            if (asset != null) {
                ObjectNode media = share.putArray("media").addObject();
                media.put("status", "READY");
                media.put("media", asset);
            }
            body.putObject("visibility").put("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC");
            LinkedInApi.Reply reply = api.share(token, json.writeValueAsString(body));
            if (!reply.ok()) {
                throw new BadRequestException(problem(reply));
            }
            String id = reply.restliId() != null ? reply.restliId() : read(reply.body()).path("id").asText("");
            return id.isEmpty() ? "https://www.linkedin.com/feed/" : "https://www.linkedin.com/feed/update/" + id + "/";
        } catch (IOException unreachable) {
            throw new BadRequestException("Couldn't reach LinkedIn. Check the internet connection and try again.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("Posting was interrupted. Check LinkedIn before trying again.");
        }
    }

    /** Uploads the picture and gives back the id LinkedIn knows it by. */
    private String upload(String token, String author, byte[] picture) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        ObjectNode request = body.putObject("registerUploadRequest");
        request.putArray("recipes").add("urn:li:digitalmediaRecipe:feedshare-image");
        request.put("owner", author);
        ObjectNode relationship = request.putArray("serviceRelationships").addObject();
        relationship.put("relationshipType", "OWNER");
        relationship.put("identifier", "urn:li:userGeneratedContent");
        LinkedInApi.Reply registered = api.registerUpload(token, json.writeValueAsString(body));
        if (!registered.ok()) {
            throw new BadRequestException(problem(registered));
        }
        JsonNode value = read(registered.body()).path("value");
        String uploadUrl = value.path("uploadMechanism")
                .path("com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest").path("uploadUrl").asText("");
        String asset = value.path("asset").asText("");
        if (uploadUrl.isEmpty() || asset.isEmpty()) {
            throw new BadRequestException("LinkedIn didn't take the picture. Try again, or remove the picture and post without it.");
        }
        LinkedInApi.Reply uploaded = api.upload(token, uploadUrl, picture);
        if (!uploaded.ok()) {
            throw new BadRequestException("LinkedIn didn't take the picture (" + uploaded.status()
                    + "). Try again, or remove the picture and post without it.");
        }
        return asset;
    }

    /** A sentence for the team about a request LinkedIn turned down. */
    String problem(LinkedInApi.Reply reply) {
        String message = apiMessage(reply.body());
        return switch (reply.status()) {
            case 401 -> "LinkedIn didn't accept your connection. Connect again under Plan on Marketing.";
            case 403 -> "LinkedIn didn't allow this. Check that the firm's LinkedIn app has Share on LinkedIn added.";
            case 422 -> "LinkedIn turned the post down" + (message.isEmpty() ? "." : ": " + message);
            case 429 -> "LinkedIn's daily limit for posting is reached. Try again tomorrow.";
            default -> reply.status() >= 500
                    ? "LinkedIn had a problem (" + reply.status() + "). Check LinkedIn before trying again."
                    : "LinkedIn turned the request down (" + reply.status() + ")" + (message.isEmpty() ? "." : ": " + message);
        };
    }

    // Storage --------------------------------------------------------------------------------

    private Connection connection(long userId) {
        Map<String, Object> account = account(userId);
        if (account == null) {
            return null;
        }
        Instant expires = instant(account.get("expires_at"));
        Instant now = clock.instant();
        String name = (String) account.get("name");
        return new Connection(name == null ? null : encryptor.decrypt(name), expires, expires.isBefore(now),
                expires.isBefore(now.plus(EXPIRING)));
    }

    /** A stored time, however the driver hands it over. */
    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return Instant.EPOCH;
    }

    private Map<String, Object> account(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT person_urn, name, access_token, expires_at FROM linkedin_account WHERE user_id = ?", userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, String> app() {
        Map<String, String> values = new HashMap<>();
        jdbc.query("SELECT name, value FROM firm_setting WHERE name IN (?, ?)", row -> {
            String value = row.getString("value");
            if (value != null) {
                values.put(row.getString("name"), encryptor.decrypt(value));
            }
        }, CLIENT_ID, CLIENT_SECRET);
        return values;
    }

    private void write(String name, String value) {
        jdbc.update("""
                INSERT INTO firm_setting (name, value) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """, name, value == null ? null : encryptor.encrypt(value));
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body == null ? "" : body);
        } catch (JsonProcessingException unreadable) {
            return json.createObjectNode();
        }
    }

    private String apiMessage(String body) {
        JsonNode parsed = read(body);
        String message = parsed.path("message").asText(parsed.path("error_description").asText(""));
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String back(String outcome, String reason) {
        return "/marketing?view=plan&linkedin=" + outcome
                + (reason == null ? "" : "&reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8).replace("+", "%20"));
    }
}
