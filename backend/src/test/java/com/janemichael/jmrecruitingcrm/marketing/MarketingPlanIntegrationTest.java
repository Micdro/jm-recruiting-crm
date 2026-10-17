package com.janemichael.jmrecruitingcrm.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.TestSettings;
import com.janemichael.jmrecruitingcrm.ai.AiProviderId;
import com.janemichael.jmrecruitingcrm.ai.AiSettings;
import com.janemichael.jmrecruitingcrm.ai.OpenAiApi;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.linkedin.LinkedInApi;
import com.janemichael.jmrecruitingcrm.prospects.ClaudeApi;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The content plan, setting up a week with AI, post pictures and posting to LinkedIn, against a
 * real database and stand-in Claude, OpenAI and LinkedIn APIs.
 */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, "app.prospects.initial-delay-ms=86400000",
        "app.signals.initial-delay-ms=86400000"})
@Transactional
class MarketingPlanIntegrationTest {

    private static final String OWNER = "plan-owner@jane-michael.test";
    private static final String MEMBER = "plan-member@jane-michael.test";
    private static final String CLAUDE_KEY = "sk-ant-api03-plan-test-1234";
    private static final String OPENAI_KEY = "sk-proj-plan-test-5678";
    /** The start of a PNG file, then enough bytes to be a picture as far as the CRM checks. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 1, 2, 3};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1};

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AiSettings aiSettings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private ClaudeApi claude;

    @MockitoBean
    private OpenAiApi openAi;

    @MockitoBean
    private LinkedInApi linkedIn;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private Cookie csrf;
    private AppUser member;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        account(OWNER, "Michael Owner", UserRole.ADMIN, true);
        member = account(MEMBER, "Jane Member", UserRole.MEMBER, false);
        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        // Settings left by other test classes would change what these tests see.
        jdbc.update("DELETE FROM firm_setting WHERE name LIKE 'prospects.%' OR name LIKE 'ai.%' "
                + "OR name LIKE 'marketing.%' OR name LIKE 'linkedin.%'");
    }

    @Test
    void savesTheStyleThePostingDaysAndTheMonthsFocus() throws Exception {
        mockMvc.perform(send(MEMBER, "MEMBER", put("/api/marketing/plan"), Map.of(
                        "style", "Short sentences. Talk like a recruiter, not a brand.",
                        "pictureStyle", "Navy and gold, flat shapes",
                        "rhythm", List.of(
                                Map.of("day", "THURSDAY", "theme", "BFSI_AI_SECURITY", "account", "COMPANY_PAGE"),
                                Map.of("day", "TUESDAY", "theme", "FDE_HIRING", "account", "PERSONAL",
                                        "ownerId", member.getId()),
                                // The same person twice on the same day keeps one.
                                Map.of("day", "TUESDAY", "account", "PERSONAL", "ownerId", member.getId())),
                        "month", "2026-11",
                        "focus", "Insurers hiring FDEs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rhythm.length()").value(2))
                .andExpect(jsonPath("$.rhythm[0].day").value("TUESDAY"))
                .andExpect(jsonPath("$.rhythm[0].ownerName").value("Jane Member"))
                .andExpect(jsonPath("$.rhythm[1].account").value("COMPANY_PAGE"));

        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/plan?month=2026-11"), null))
                .andExpect(jsonPath("$.style").value("Short sentences. Talk like a recruiter, not a brand."))
                .andExpect(jsonPath("$.pictureStyle").value("Navy and gold, flat shapes"))
                .andExpect(jsonPath("$.focus").value("Insurers hiring FDEs"));
        // Each month has its own focus.
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/plan?month=2026-12"), null))
                .andExpect(jsonPath("$.focus").doesNotExist())
                .andExpect(jsonPath("$.rhythm.length()").value(2));
    }

    @Test
    void setsUpTheWeeksOpenPostingDaysWithDraftsInTheTeamsStyle() throws Exception {
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        LocalDate monday = LocalDate.now(clock).plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        mockMvc.perform(send(MEMBER, "MEMBER", put("/api/marketing/plan"), Map.of(
                        "style", "Always end with a question.",
                        "rhythm", List.of(
                                Map.of("day", "TUESDAY", "theme", "FDE_HIRING", "ownerId", member.getId()),
                                Map.of("day", "THURSDAY", "theme", "BFSI_AI_SECURITY", "account", "COMPANY_PAGE"),
                                Map.of("day", "FRIDAY", "theme", "AI_PLATFORM_HIRING", "ownerId", member.getId())),
                        "month", monday.plusDays(3).toString().substring(0, 7),
                        "focus", "Model risk reviews")))
                .andExpect(status().isOk());
        // Tuesday already has Jane's post, so it's left alone.
        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts"), Map.of(
                        "title", "Already planned", "plannedFor", monday.plusDays(1).toString())))
                .andExpect(status().isCreated());

        when(claude.messages(eq(CLAUDE_KEY), anyString())).thenReturn(new ClaudeApi.Reply(200, """
                {"stop_reason": "tool_use",
                 "content": [{"type": "tool_use", "id": "toolu_1", "name": "write", "input": {"posts": [
                    {"slot": "2", "title": "Platform engineers who pass model risk", "body": "Friday post. What do you test?"},
                    {"slot": "1", "title": "What banks ask an AI vendor", "body": "Thursday post \\u2014 for the page."}]}}],
                 "usage": {"input_tokens": 3000, "output_tokens": 900}}
                """, null));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/week/setup"),
                        Map.of("date", monday.plusDays(2).toString(), "focus", "Conference week")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(2))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.provider").value("Claude"))
                .andExpect(jsonPath("$.created[0].plannedFor").value(monday.plusDays(3).toString()))
                .andExpect(jsonPath("$.created[0].account").value("COMPANY_PAGE"))
                .andExpect(jsonPath("$.created[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.created[0].body").value("Thursday post, for the page."))
                .andExpect(jsonPath("$.created[1].plannedFor").value(monday.plusDays(4).toString()))
                .andExpect(jsonPath("$.created[1].title").value("Platform engineers who pass model risk"))
                .andExpect(jsonPath("$.created[1].ownerName").value("Jane Member"))
                .andExpect(jsonPath("$.created[1].theme").value("AI_PLATFORM_HIRING"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(claude).messages(eq(CLAUDE_KEY), sent.capture());
        JsonNode request = json.readTree(sent.getValue());
        assertThat(request.path("system").asText()).contains("style guide").contains("Always end with a question.");
        assertThat(request.path("tools").get(0).path("input_schema").path("properties").path("posts")
                .path("items").path("required").toString()).contains("slot").contains("title").contains("body");
        assertThat(request.path("messages").get(0).path("content").asText())
                .contains("Write 2 LinkedIn posts")
                .contains("The team's focus this month: Model risk reviews")
                .contains("Focus this week: Conference week")
                .contains("1. Thursday").contains("For the firm's company page.")
                .contains("2. Friday").contains("For Jane's own profile.")
                .contains("- Already planned");

        // Nothing left to fill: it says so, and doesn't call the AI again.
        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/week/setup"),
                        Map.of("date", monday.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("already has a post")));
    }

    @Test
    void uploadsShowsAndRemovesAPicture() throws Exception {
        long id = created(MEMBER, Map.of("title", "Picture test post"));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/image"), null)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("not a picture".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("JPG, PNG, GIF or WebP")));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/image"), null)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(PNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picture.source").value("UPLOAD"))
                .andExpect(jsonPath("$.picture.ready").value(true))
                .andExpect(jsonPath("$.picture.url").value(startsWith("/api/marketing/posts/" + id + "/image?v=")));

        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/posts/" + id + "/image"), null))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(PNG));
        // Stored encrypted. (The test's own transaction hasn't written the post's change yet.)
        entityManager.flush();
        byte[] stored = jdbc.queryForObject("SELECT b.content FROM file_blob b JOIN content_post p "
                + "ON p.image_key = b.blob_key WHERE p.id = ?", byte[].class, id);
        assertThat(Arrays.equals(stored, PNG)).isFalse();

        mockMvc.perform(send(MEMBER, "MEMBER", delete("/api/marketing/posts/" + id + "/image"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picture").doesNotExist());
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/posts/" + id + "/image"), null))
                .andExpect(status().isNotFound());
    }

    @Test
    void makesAPictureWithOpenAiWhicheverAiWrites() throws Exception {
        long id = created(MEMBER, Map.of("title", "Why banks hire FDEs first", "body", "Banks buy outcomes."));
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        mockMvc.perform(send(MEMBER, "MEMBER", put("/api/marketing/plan"), Map.of(
                        "pictureStyle", "Flat navy shapes", "month", LocalDate.now(clock).toString().substring(0, 7))))
                .andExpect(status().isOk());

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/image/generate"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("needs an OpenAI API key")));

        aiSettings.saveKey(AiProviderId.OPENAI, OPENAI_KEY);
        when(openAi.images(eq(OPENAI_KEY), anyString())).thenReturn(new OpenAiApi.Reply(200, """
                {"data": [{"b64_json": "%s"}], "usage": {"input_tokens": 200, "output_tokens": 1000}}
                """.formatted(Base64.getEncoder().encodeToString(JPEG))));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/image/generate"),
                        Map.of("direction", "A bridge at dawn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post.picture.source").value("AI"))
                // 200 words in at $5 a million and 1,000 picture tokens at $30 a million: 3.1 cents, rounded up.
                .andExpect(jsonPath("$.costCents").value(4));
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/posts/" + id + "/image"), null))
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(content().bytes(JPEG));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(openAi).images(eq(OPENAI_KEY), sent.capture());
        JsonNode request = json.readTree(sent.getValue());
        assertThat(request.path("model").asText()).isEqualTo("gpt-image-2.5-flare");
        assertThat(request.path("prompt").asText())
                .contains("Picture style: Flat navy shapes")
                .contains("Why banks hire FDEs first")
                .contains("Direction: A bridge at dawn")
                .contains("no words, letters, numbers or logos");
        assertThat(aiSettings.spentCents(LocalDate.now(clock))).isEqualTo(4);
        verify(claude, never()).messages(anyString(), anyString());
    }

    @Test
    void connectsAPersonsLinkedInAndPostsWithThePicture() throws Exception {
        long id = created(MEMBER, Map.of("title", "Ready to post", "body", "Banks buy outcomes first.\n\nWhat do you see?"));
        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/image"), null)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(PNG))
                .andExpect(status().isOk());

        // No app yet: connecting says who can add it.
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/linkedin/connect"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("Michael Owner can add it")));
        // Only the owner adds the firm's app.
        Map<String, String> app = Map.of("clientId", "86abcdefghijkl", "clientSecret", "WPL_AP1.secret.value");
        mockMvc.perform(send(MEMBER, "MEMBER", put("/api/linkedin/app"), app))
                .andExpect(status().isForbidden());
        mockMvc.perform(send(OWNER, "ADMIN", put("/api/linkedin/app"), app))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appAdded").value(true))
                .andExpect(jsonPath("$.clientIdHint").value("ijkl"))
                .andExpect(jsonPath("$.redirectUrl").value("http://localhost/api/linkedin/callback"));

        String url = json.readTree(mockMvc.perform(send(MEMBER, "MEMBER", get("/api/linkedin/connect"), null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("url").asText();
        assertThat(url).startsWith("https://www.linkedin.com/oauth/v2/authorization?response_type=code")
                .contains("client_id=86abcdefghijkl")
                .contains("redirect_uri=http%3A%2F%2Flocalhost%2Fapi%2Flinkedin%2Fcallback")
                .contains("scope=openid%20profile%20w_member_social");
        String state = URLDecoder.decode(url.replaceAll(".*[?&]state=([^&]+).*", "$1"), StandardCharsets.UTF_8);

        // A made-up state is turned away.
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/linkedin/callback?code=abc&state=forged"), null))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("/marketing?view=plan&linkedin=failed")));

        when(linkedIn.token("86abcdefghijkl", "WPL_AP1.secret.value", "the-code", "http://localhost/api/linkedin/callback"))
                .thenReturn(new LinkedInApi.Reply(200, "{\"access_token\": \"member-token\", \"expires_in\": 5184000}", null));
        when(linkedIn.userInfo("member-token"))
                .thenReturn(new LinkedInApi.Reply(200, "{\"sub\": \"abc123\", \"name\": \"Jane Member\"}", null));
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/linkedin/callback?code=the-code&state=" + state), null))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/marketing?view=plan&linkedin=connected"));
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/linkedin"), null))
                .andExpect(jsonPath("$.me.name").value("Jane Member"))
                .andExpect(jsonPath("$.me.expired").value(false))
                .andExpect(jsonPath("$.canManage").value(false));
        String token = jdbc.queryForObject("SELECT access_token FROM linkedin_account WHERE user_id = ?",
                String.class, member.getId());
        assertThat(token).doesNotContain("member-token");

        // Someone else can't post Jane's post to their own profile.
        mockMvc.perform(send(OWNER, "ADMIN", post("/api/marketing/posts/" + id + "/publish"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("goes on Jane Member's profile")));

        when(linkedIn.registerUpload(eq("member-token"), anyString())).thenReturn(new LinkedInApi.Reply(200, """
                {"value": {"uploadMechanism": {"com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest":
                    {"uploadUrl": "https://api.linkedin.com/mediaUpload/abc"}},
                  "asset": "urn:li:digitalmediaAsset:A1"}}
                """, null));
        when(linkedIn.upload(eq("member-token"), eq("https://api.linkedin.com/mediaUpload/abc"), any()))
                .thenReturn(new LinkedInApi.Reply(201, "", null));
        when(linkedIn.share(eq("member-token"), anyString()))
                .thenReturn(new LinkedInApi.Reply(201, "", "urn:li:share:999"));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/publish"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.postedOn").value(LocalDate.now(clock).toString()))
                .andExpect(jsonPath("$.postUrl").value("https://www.linkedin.com/feed/update/urn:li:share:999/"));

        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        verify(linkedIn).upload(eq("member-token"), eq("https://api.linkedin.com/mediaUpload/abc"), uploaded.capture());
        assertThat(uploaded.getValue()).isEqualTo(PNG);
        ArgumentCaptor<String> shared = ArgumentCaptor.forClass(String.class);
        verify(linkedIn).share(eq("member-token"), shared.capture());
        JsonNode body = json.readTree(shared.getValue());
        assertThat(body.path("author").asText()).isEqualTo("urn:li:person:abc123");
        JsonNode share = body.path("specificContent").path("com.linkedin.ugc.ShareContent");
        assertThat(share.path("shareCommentary").path("text").asText()).isEqualTo("Banks buy outcomes first.\n\nWhat do you see?");
        assertThat(share.path("shareMediaCategory").asText()).isEqualTo("IMAGE");
        assertThat(share.path("media").get(0).path("media").asText()).isEqualTo("urn:li:digitalmediaAsset:A1");

        // Posted once; a second click doesn't post it again.
        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/publish"), null))
                .andExpect(status().isConflict());
    }

    @Test
    void companyPagePostsStayCopyAndPaste() throws Exception {
        long id = created(MEMBER, Map.of("title", "Page post", "body", "From the firm.", "account", "COMPANY_PAGE"));
        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/publish"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("Copy the post and post it on the page")));
        verify(linkedIn, never()).share(anyString(), anyString());
    }

    @Test
    void upcomingShowsWhatsStillToPostInOrder() throws Exception {
        LocalDate today = LocalDate.now(clock);
        created(MEMBER, Map.of("title", "Later post", "plannedFor", today.plusDays(5).toString()));
        created(MEMBER, Map.of("title", "Missed post", "plannedFor", today.minusDays(2).toString()));
        created(MEMBER, Map.of("title", "Too old", "plannedFor", today.minusDays(30).toString()));
        created(MEMBER, Map.of("title", "Idea without a day"));

        String body = mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/upcoming"), null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> titles = json.readTree(body).findValuesAsText("title");
        assertThat(titles).containsSubsequence("Missed post", "Later post").doesNotContain("Too old", "Idea without a day");
    }

    private long created(String email, Map<String, Object> post) throws Exception {
        return json.readTree(mockMvc.perform(send(email, "MEMBER", post("/api/marketing/posts"), post))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
    }

    private AppUser account(String email, String name, UserRole role, boolean accountOwner) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setRole(role);
        user.setActive(true);
        user.setAccountOwner(accountOwner);
        user.setPasswordHash(passwordEncoder.encode("plan-test-password-1"));
        return users.save(user);
    }

    private MockHttpServletRequestBuilder send(String email, String role, MockHttpServletRequestBuilder request,
                                               Object payload) throws Exception {
        MockHttpServletRequestBuilder signedIn = request
                .with(user(email).roles(role))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
        return payload == null
                ? signedIn
                : signedIn.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }
}
