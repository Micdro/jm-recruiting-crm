package com.janemichael.jmrecruitingcrm.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.TestSettings;
import com.janemichael.jmrecruitingcrm.ai.AiProviderId;
import com.janemichael.jmrecruitingcrm.ai.AiSettings;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Monday brief, against a real database and a stand-in Claude API. */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, "app.prospects.initial-delay-ms=86400000",
        "app.signals.initial-delay-ms=86400000"})
@Transactional
class BriefIntegrationTest {

    private static final String OWNER = "brief-owner@jane-michael.test";
    private static final String MEMBER = "brief-member@jane-michael.test";
    private static final String CLAUDE_KEY = "sk-ant-api03-brief-test-1234";

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
        jdbc.update("DELETE FROM firm_setting WHERE name LIKE 'prospects.%' OR name LIKE 'ai.%' OR name LIKE 'brief.%'");
    }

    @Test
    void showsTheWeeksFactsAndSaysWhoCanAddAKey() throws Exception {
        LocalDate monday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        mockMvc.perform(send(get("/api/brief")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekOf").value(monday.toString()))
                .andExpect(jsonPath("$.facts.lastWeek").value(monday.minusWeeks(1).toString()))
                .andExpect(jsonPath("$.facts.outreach.target").value(150))
                .andExpect(jsonPath("$.written").doesNotExist())
                .andExpect(jsonPath("$.canWrite").value(false))
                .andExpect(jsonPath("$.provider").value("Claude"))
                .andExpect(jsonPath("$.owner").value("Michael Owner"));

        mockMvc.perform(send(post("/api/brief/write")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("Writing the Monday brief needs a Claude API key")));
        verify(claude, never()).messages(anyString(), anyString());
    }

    @Test
    void writesTheBriefOnceAWeekForEveryoneAndRewritesOnRequest() throws Exception {
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        when(claude.messages(eq(CLAUDE_KEY), anyString())).thenReturn(new ClaudeApi.Reply(200, """
                {"stop_reason": "tool_use",
                 "content": [{"type": "tool_use", "id": "toolu_1", "name": "write", "input": {
                    "headline": "A steady week \\u2014 two searches need a push.",
                    "lastWeek": ["- 142 touches against 150 each", "Marisol moved to Interviewing"],
                    "watch": ["1. Harbor Mutual has been quiet for 16 days"],
                    "focus": ["Michael: call Harbor Mutual", "Jane: send the Cobalt shortlist", "Post three times", "Extra"]}}],
                 "usage": {"input_tokens": 1500, "output_tokens": 300}}
                """, null));

        mockMvc.perform(send(post("/api/brief/write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canWrite").value(true))
                .andExpect(jsonPath("$.written.headline").value("A steady week, two searches need a push."))
                .andExpect(jsonPath("$.written.lastWeek[0]").value("142 touches against 150 each"))
                .andExpect(jsonPath("$.written.watch[0]").value("Harbor Mutual has been quiet for 16 days"))
                .andExpect(jsonPath("$.written.focus.length()").value(3))
                .andExpect(jsonPath("$.written.writtenBy").value("Jane Member"))
                .andExpect(jsonPath("$.written.provider").value("Claude"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(claude).messages(eq(CLAUDE_KEY), sent.capture());
        JsonNode request = json.readTree(sent.getValue());
        assertThat(request.path("system").asText()).contains("Monday brief").contains("never invent");
        assertThat(request.path("messages").get(0).path("content").asText())
                .contains("Last week's outreach:").contains("Open searches that need attention:")
                .contains("Follow-ups due now:");

        // Stored encrypted, so the database alone can't read it.
        String stored = jdbc.queryForObject("SELECT value FROM firm_setting WHERE name LIKE 'brief.week.%'", String.class);
        assertThat(stored).doesNotContain("steady week");

        // Already written this week: everyone gets the same one, without paying again.
        mockMvc.perform(send(post("/api/brief/write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written.headline").value("A steady week, two searches need a push."));
        mockMvc.perform(send(get("/api/brief")))
                .andExpect(jsonPath("$.written.focus[0]").value("Michael: call Harbor Mutual"));
        verify(claude, times(1)).messages(anyString(), anyString());

        mockMvc.perform(send(post("/api/brief/write").param("rewrite", "true")))
                .andExpect(status().isOk());
        verify(claude, times(2)).messages(anyString(), anyString());
    }

    private void account(String email, String name, UserRole role, boolean accountOwner) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setRole(role);
        user.setActive(true);
        user.setAccountOwner(accountOwner);
        user.setPasswordHash(passwordEncoder.encode("brief-test-password-1"));
        users.save(user);
    }

    private MockHttpServletRequestBuilder send(MockHttpServletRequestBuilder request) {
        return request
                .with(user(MEMBER).roles("MEMBER"))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
    }
}
