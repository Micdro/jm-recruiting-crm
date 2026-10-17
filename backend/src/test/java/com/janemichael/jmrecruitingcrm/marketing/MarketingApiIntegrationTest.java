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
import com.janemichael.jmrecruitingcrm.prospects.ClaudeApi;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Marketing page and the AI settings, against a real database and stand-in Claude and OpenAI APIs. */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, "app.prospects.initial-delay-ms=86400000",
        "app.signals.initial-delay-ms=86400000"})
@Transactional
class MarketingApiIntegrationTest {

    private static final String OWNER = "marketing-owner@jane-michael.test";
    private static final String MEMBER = "marketing-member@jane-michael.test";
    private static final String CLAUDE_KEY = "sk-ant-api03-marketing-test-1234";
    private static final String OPENAI_KEY = "sk-proj-marketing-test-5678";

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

    @MockitoBean
    private ClaudeApi claude;

    @MockitoBean
    private OpenAiApi openAi;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private Cookie csrf;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        account(OWNER, "Michael Owner", UserRole.ADMIN, true);
        account(MEMBER, "Jane Member", UserRole.MEMBER, false);
        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        // Settings left by other test classes would change what these tests see.
        jdbc.update("DELETE FROM firm_setting WHERE name LIKE 'prospects.%' OR name LIKE 'ai.%'");
    }

    @Test
    void plansAPostFromIdeaToPostedAndKeepsItsResults() throws Exception {
        long id = created(mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts"),
                        Map.of("title", "Why banks want FDEs before platform engineers", "theme", "FDE_HIRING")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IDEA"))
                .andExpect(jsonPath("$.account").value("PERSONAL"))
                .andExpect(jsonPath("$.ownerName").value("Jane Member"))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/week?date=2026-10-21"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start").value("2026-10-19"))
                .andExpect(jsonPath("$.end").value("2026-10-25"))
                .andExpect(jsonPath("$.planned.length()").value(0))
                .andExpect(jsonPath("$.ideas[0].id").value(id));

        // Words in it and a day: now a draft on the calendar.
        mockMvc.perform(send(MEMBER, "MEMBER", put("/api/marketing/posts/" + id), Map.of(
                        "title", "Why banks want FDEs before platform engineers", "theme", "FDE_HIRING",
                        "body", "Banks buy outcomes first.", "plannedFor", "2026-10-20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.ownerName").value("Jane Member"));

        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/week?date=2026-10-25"), null))
                .andExpect(jsonPath("$.planned[0].id").value(id))
                .andExpect(jsonPath("$.planned[0].plannedFor").value("2026-10-20"))
                .andExpect(jsonPath("$.ideas.length()").value(0));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/posted"),
                        Map.of("postUrl", "https://www.linkedin.com/posts/marketing-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.postedOn").value(LocalDate.now(clock).toString()));

        mockMvc.perform(send(MEMBER, "MEMBER", put("/api/marketing/posts/" + id + "/results"),
                        Map.of("impressions", 1200, "comments", 9, "conversations", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impressions").value(1200))
                .andExpect(jsonPath("$.conversations").value(2));

        mockMvc.perform(send(MEMBER, "MEMBER", put("/api/marketing/posts/" + id + "/results"),
                        Map.of("impressions", -1)))
                .andExpect(status().isBadRequest());

        // Encrypted at rest, like the rest of the CRM's text.
        String stored = jdbc.queryForObject("SELECT title FROM content_post WHERE id = ?", String.class, id);
        assertThat(stored).doesNotContain("Why banks");
    }

    @Test
    void draftsWithClaudeByDefaultWithoutSavingAndCountsTheCost() throws Exception {
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        long id = created(mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts"), Map.of(
                        "title", "What a forward deployed engineer does in week one",
                        "notes", "Most of week one is in the customer's environment.",
                        "account", "COMPANY_PAGE")))
                .andReturn().getResponse().getContentAsString());
        when(claude.messages(eq(CLAUDE_KEY), anyString())).thenReturn(new ClaudeApi.Reply(200, """
                {"stop_reason": "tool_use",
                 "content": [{"type": "tool_use", "id": "toolu_1", "name": "write",
                              "input": {"body": "Week one is on site — not in a sandbox."}}],
                 "usage": {"input_tokens": 2500, "output_tokens": 300}}
                """, null));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/draft"),
                        Map.of("direction", "Keep it short")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Week one is on site, not in a sandbox."))
                .andExpect(jsonPath("$.provider").value("Claude"))
                .andExpect(jsonPath("$.costCents").value(1));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(claude).messages(eq(CLAUDE_KEY), sent.capture());
        JsonNode request = json.readTree(sent.getValue());
        assertThat(request.path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(request.path("tool_choice").path("name").asText()).isEqualTo("write");
        JsonNode schema = request.path("tools").get(0).path("input_schema");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("body");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(request.path("messages").get(0).path("content").asText())
                .contains("the firm's company page")
                .contains("Topic: What a forward deployed engineer does in week one")
                .contains("Most of week one is in the customer's environment.")
                .contains("Direction for this draft: Keep it short");
        assertThat(request.path("system").asText()).contains("Never name a client").contains("No em dashes");

        assertThat(aiSettings.spentCents(LocalDate.now(clock))).isEqualTo(1);
        // A draft is for reading; the post is unchanged until someone saves it.
        mockMvc.perform(send(MEMBER, "MEMBER", get("/api/marketing/posts/" + id), null))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void theOwnerCanSwitchToOpenAiAndEveryoneThenWritesWithIt() throws Exception {
        mockMvc.perform(send(OWNER, "ADMIN", put("/api/ai/openai-key"), Map.of("apiKey", CLAUDE_KEY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("That's a Claude key")));

        when(openAi.models(OPENAI_KEY)).thenReturn(new OpenAiApi.Reply(200, "{\"data\": []}"));
        mockMvc.perform(send(OWNER, "ADMIN", put("/api/ai/openai-key"), Map.of("apiKey", " " + OPENAI_KEY + " ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.provider").value("CLAUDE"))
                .andExpect(jsonPath("$.providers[1].id").value("OPENAI"))
                .andExpect(jsonPath("$.providers[1].hasKey").value(true))
                .andExpect(jsonPath("$.providers[1].keyHint").value("5678"));

        mockMvc.perform(send(MEMBER, "MEMBER", put("/api/ai/provider"), Map.of("provider", "OPENAI")))
                .andExpect(status().isForbidden());
        mockMvc.perform(send(OWNER, "ADMIN", put("/api/ai/provider"), Map.of("provider", "OPENAI", "model", "gpt-1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(send(OWNER, "ADMIN", put("/api/ai/provider"),
                        Map.of("provider", "OPENAI", "model", "gpt-6-luna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("OPENAI"))
                .andExpect(jsonPath("$.providers[1].model").value("gpt-6-luna"));

        when(openAi.responses(eq(OPENAI_KEY), anyString())).thenReturn(new OpenAiApi.Reply(200, """
                {"status": "completed",
                 "output": [{"type": "reasoning", "summary": []},
                            {"type": "message", "role": "assistant",
                             "content": [{"type": "output_text",
                                          "text": "{\\"ideas\\": [\\"The FDE interview loop banks trust\\", \\" \\", \\"Platform engineers who pass model risk review\\", \\"A third idea\\"]}"}]}],
                 "usage": {"input_tokens": 2000, "output_tokens": 500}}
                """));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/ideas/suggest"),
                        Map.of("count", 2, "focus", "FDE hiring")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("OpenAI"))
                .andExpect(jsonPath("$.ideas.length()").value(2))
                .andExpect(jsonPath("$.ideas[0]").value("The FDE interview loop banks trust"))
                .andExpect(jsonPath("$.ideas[1]").value("Platform engineers who pass model risk review"))
                .andExpect(jsonPath("$.costCents").value(1));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(openAi).responses(eq(OPENAI_KEY), sent.capture());
        JsonNode request = json.readTree(sent.getValue());
        assertThat(request.path("model").asText()).isEqualTo("gpt-6-luna");
        assertThat(request.path("store").asBoolean(true)).isFalse();
        assertThat(request.path("instructions").asText()).contains("Jane Michael AI Talent Search");
        assertThat(request.path("input").asText()).contains("Suggest 2 ideas").contains("Focus this week: FDE hiring");
        JsonNode format = request.path("text").path("format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("strict").asBoolean()).isTrue();
        assertThat(format.path("schema").path("properties").path("ideas").path("type").asText()).isEqualTo("array");
        verify(claude, never()).messages(anyString(), anyString());
    }

    @Test
    void needsAKeyAndRoomInTheSharedLimits() throws Exception {
        long id = created(mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts"),
                        Map.of("title", "Limits test post"))).andReturn().getResponse().getContentAsString());

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/draft"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("Drafting needs a Claude API key")));

        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        aiSettings.saveLimits(1, 30);
        aiSettings.addSpend(LocalDate.now(clock), 98);
        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/draft"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value(containsString("Today's AI limit of $1 is used up")));
        verify(claude, never()).messages(anyString(), anyString());
    }

    @Test
    void aCutOffAnswerFailsButStillCountsWhatItCost() throws Exception {
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        long id = created(mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts"),
                        Map.of("title", "Cut off test post"))).andReturn().getResponse().getContentAsString());
        when(claude.messages(eq(CLAUDE_KEY), anyString())).thenReturn(new ClaudeApi.Reply(200, """
                {"stop_reason": "max_tokens", "content": [{"type": "text", "text": "Thinking..."}],
                 "usage": {"input_tokens": 100000, "output_tokens": 1500}}
                """, null));

        mockMvc.perform(send(MEMBER, "MEMBER", post("/api/marketing/posts/" + id + "/draft"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("cut off")));
        // $0.20 of input and $0.015 of output, rounded up.
        assertThat(aiSettings.spentCents(LocalDate.now(clock))).isEqualTo(22);
    }

    private AppUser account(String email, String name, UserRole role, boolean accountOwner) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setRole(role);
        user.setActive(true);
        user.setAccountOwner(accountOwner);
        user.setPasswordHash(passwordEncoder.encode("marketing-test-password-1"));
        return users.save(user);
    }

    private long created(String body) throws Exception {
        return json.readTree(body).path("id").asLong();
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
