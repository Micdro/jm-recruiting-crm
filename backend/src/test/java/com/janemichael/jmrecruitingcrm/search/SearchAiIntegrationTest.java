package com.janemichael.jmrecruitingcrm.search;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

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

/** Intake notes, job descriptions and rationales written with AI, against a real database and a stand-in Claude. */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, "app.prospects.initial-delay-ms=86400000",
        "app.signals.initial-delay-ms=86400000"})
@Transactional
class SearchAiIntegrationTest {

    private static final String OWNER = "search-ai-owner@jane-michael.test";
    private static final String MEMBER = "search-ai-member@jane-michael.test";
    private static final String CLAUDE_KEY = "sk-ant-api03-search-ai-test-1234";

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
        jdbc.update("DELETE FROM firm_setting WHERE name LIKE 'prospects.%' OR name LIKE 'ai.%'");
    }

    @Test
    void readsIntakeNotesIntoASearchWithAJobDescriptionWithoutSavingIt() throws Exception {
        long company = created("/api/companies", Map.of("name", "Cobaltline Insurance"));
        long manager = created("/api/contacts", Map.of("name", "Dana Reyes", "companyId", company));
        Map<String, Object> notes = Map.of("notes", "Dana at Cobaltline. FDE, hybrid NYC, 180-230 base, need ASAP. 20%.");

        mockMvc.perform(send(post("/api/searches/read-intake"), notes))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("Reading the intake notes needs a Claude API key")));
        verify(claude, never()).messages(anyString(), anyString());

        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        claudeAnswers("""
                {"company": "Cobaltline Insurance, Inc.", "hiringManager": "dana reyes",
                 "title": "Forward Deployed Engineer", "track": "production_ai_engineering",
                 "location": "New York, NY", "workModel": "hybrid", "baseMin": "$180,000", "baseMax": "230k",
                 "timeline": "ASAP", "engagementType": "", "feePercent": "20%",
                 "productionOutcome": "Claims triage models live at two carriers.",
                 "technicalScope": "Python, Kubernetes, customer VPCs.", "accountability": "Owns deployments end to end.",
                 "regulatoryConstraints": "Model risk review before go-live.", "notes": "- Two rounds, then the CTO.",
                 "jobDescription": "About the role\\nShip AI into insurers \\u2014 for real.\\n\\nWhat you'll own\\nDeployments."}
                """);

        String before = String.valueOf(jdbc.queryForObject("SELECT count(*) FROM job_search", Long.class));
        mockMvc.perform(send(post("/api/searches/read-intake"), notes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("Claude"))
                .andExpect(jsonPath("$.companyHeard").value("Cobaltline Insurance, Inc."))
                .andExpect(jsonPath("$.search.companyId").value(company))
                .andExpect(jsonPath("$.search.hiringManagerId").value(manager))
                .andExpect(jsonPath("$.hiringManager").doesNotExist())
                .andExpect(jsonPath("$.search.title").value("Forward Deployed Engineer"))
                .andExpect(jsonPath("$.search.track").value("PRODUCTION_AI_ENGINEERING"))
                .andExpect(jsonPath("$.search.workModel").value("HYBRID"))
                .andExpect(jsonPath("$.search.baseMin").value(180000))
                .andExpect(jsonPath("$.search.baseMax").value(230000))
                .andExpect(jsonPath("$.search.feePercent").value(20))
                .andExpect(jsonPath("$.search.timeline").value("ASAP"))
                .andExpect(jsonPath("$.search.engagementType").doesNotExist())
                .andExpect(jsonPath("$.search.regulatoryConstraints").value("Model risk review before go-live."))
                .andExpect(jsonPath("$.search.jobDescription").value(
                        "About the role\nShip AI into insurers, for real.\n\nWhat you'll own\nDeployments."));

        JsonNode sent = sentToClaude();
        assertThat(sent.path("system").asText()).contains("Jane Michael AI Talent Search").contains("never invent pay");
        assertThat(sent.path("messages").get(0).path("content").asText()).contains("180-230 base");
        assertThat(String.valueOf(jdbc.queryForObject("SELECT count(*) FROM job_search", Long.class))).isEqualTo(before);
    }

    @Test
    void leavesTheCompanyToPickWhenTheNotesNameOneTheCrmDoesntHave() throws Exception {
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        claudeAnswers("""
                {"company": "Harborview Mutual Test Co", "hiringManager": "Sam Lee", "title": "ML Platform Lead",
                 "track": "TECHNICAL_LEADERSHIP", "location": "", "workModel": "", "baseMin": "", "baseMax": "",
                 "timeline": "", "engagementType": "retained", "feePercent": "", "productionOutcome": "",
                 "technicalScope": "", "accountability": "", "regulatoryConstraints": "", "notes": "", "jobDescription": ""}
                """);

        mockMvc.perform(send(post("/api/searches/read-intake"), Map.of("notes", "Sam at Harborview. Platform lead.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.search.companyId").doesNotExist())
                .andExpect(jsonPath("$.companyHeard").value("Harborview Mutual Test Co"))
                .andExpect(jsonPath("$.companies.length()").value(0))
                .andExpect(jsonPath("$.hiringManager").value("Sam Lee"))
                .andExpect(jsonPath("$.search.engagementType").value("RETAINED"))
                .andExpect(jsonPath("$.search.baseMin").doesNotExist())
                .andExpect(jsonPath("$.search.jobDescription").doesNotExist());
    }

    @Test
    void writesAndSavesAJobDescriptionFromTheSearchBrief() throws Exception {
        long company = created("/api/companies", Map.of("name", "Quietharbor Bank Test", "focus",
                "Fraud detection for regional banks"));
        long search = created("/api/searches", Map.of("companyId", company, "title", "AI Platform Engineer",
                "track", "PRODUCTION_AI_ENGINEERING", "baseMin", 190000, "baseMax", 240000,
                "productionOutcome", "Real-time fraud scoring live for card authorizations."));
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        claudeAnswers("""
                {"jobDescription": "About the role\\nYou'll run fraud models in production."}
                """);

        mockMvc.perform(send(post("/api/searches/" + search + "/job-description/draft"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobDescription").value("About the role\nYou'll run fraud models in production."))
                .andExpect(jsonPath("$.provider").value("Claude"));
        String prompt = sentToClaude().path("messages").get(0).path("content").asText();
        assertThat(prompt).contains("Role: AI Platform Engineer").contains("The client does: Fraud detection for regional banks")
                .contains("Base salary: $190,000 to $240,000").contains("Real-time fraud scoring")
                .doesNotContain("Quietharbor");

        mockMvc.perform(send(put("/api/searches/" + search + "/job-description"),
                        Map.of("jobDescription", "  About the role\nEdited by hand.  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobDescription").value("About the role\nEdited by hand."))
                .andExpect(jsonPath("$.title").value("AI Platform Engineer"));
        mockMvc.perform(send(get("/api/searches/" + search), null))
                .andExpect(jsonPath("$.jobDescription").value("About the role\nEdited by hand."))
                .andExpect(jsonPath("$.productionOutcome").value("Real-time fraud scoring live for card authorizations."));
    }

    @Test
    void draftsTheRationaleFromTheCandidateTheSearchAndTheLatestCallNotes() throws Exception {
        long company = created("/api/companies", Map.of("name", "Northgate Lending Test"));
        long search = created("/api/searches", Map.of("companyId", company, "title", "Forward Deployed Engineer",
                "track", "PRODUCTION_AI_ENGINEERING", "productionOutcome", "Underwriting models live at three lenders."));
        long candidate = created("/api/candidates", Map.of("name", "Rowan Ellis", "currentTitle", "Staff ML Engineer",
                "productionEvidence", "- Owned credit decisioning models serving 4M applications a year",
                "regulatedExperience", true, "compExpectation", "$250k base"));
        mockMvc.perform(send(post("/api/candidates/" + candidate + "/activities"),
                        Map.of("type", "CALL", "notes", "- Ran model risk reviews with two banks' validators.")))
                .andExpect(status().isOk());
        long entry = json.readTree(mockMvc.perform(send(post("/api/pipeline"),
                                Map.of("searchId", search, "candidateId", candidate)))
                        .andReturn().getResponse().getContentAsString()).path("id").asLong();
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        claudeAnswers("""
                {"rationale": "Rowan owned credit decisioning in production at 4M applications a year."}
                """);

        mockMvc.perform(send(post("/api/pipeline/" + entry + "/rationale/draft"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rationale").value("Rowan owned credit decisioning in production at 4M applications a year."));

        JsonNode sent = sentToClaude();
        assertThat(sent.path("system").asText()).contains("no salary");
        assertThat(sent.path("messages").get(0).path("content").asText())
                .contains("Role: Forward Deployed Engineer")
                .contains("Underwriting models live at three lenders.")
                .contains("Production evidence: - Owned credit decisioning models")
                .contains("Has built or run production systems for banks")
                .contains("Ran model risk reviews with two banks' validators.")
                .doesNotContain("$250k");
    }

    private void claudeAnswers(String input) throws Exception {
        when(claude.messages(eq(CLAUDE_KEY), anyString())).thenReturn(new ClaudeApi.Reply(200, """
                {"stop_reason": "tool_use",
                 "content": [{"type": "tool_use", "id": "toolu_1", "name": "write", "input": %s}],
                 "usage": {"input_tokens": 2000, "output_tokens": 600}}
                """.formatted(input), null));
    }

    private JsonNode sentToClaude() throws Exception {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(claude).messages(eq(CLAUDE_KEY), sent.capture());
        return json.readTree(sent.getValue());
    }

    private long created(String path, Map<String, Object> body) throws Exception {
        return json.readTree(mockMvc.perform(send(post(path), body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
    }

    private void account(String email, String name, UserRole role, boolean accountOwner) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setRole(role);
        user.setActive(true);
        user.setAccountOwner(accountOwner);
        user.setPasswordHash(passwordEncoder.encode("search-ai-test-password-1"));
        users.save(user);
    }

    private MockHttpServletRequestBuilder send(MockHttpServletRequestBuilder request, Object payload) throws Exception {
        MockHttpServletRequestBuilder signedIn = request
                .with(user(MEMBER).roles("MEMBER"))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
        return payload == null
                ? signedIn
                : signedIn.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }
}
