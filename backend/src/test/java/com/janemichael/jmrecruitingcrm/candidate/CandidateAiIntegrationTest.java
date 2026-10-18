package com.janemichael.jmrecruitingcrm.candidate;

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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Resumes and call notes read with AI, against a real database and a stand-in Claude API. */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, "app.prospects.initial-delay-ms=86400000",
        "app.signals.initial-delay-ms=86400000"})
@Transactional
class CandidateAiIntegrationTest {

    private static final String OWNER = "cand-ai-owner@jane-michael.test";
    private static final String MEMBER = "cand-ai-member@jane-michael.test";
    private static final String CLAUDE_KEY = "sk-ant-api03-cand-ai-test-1234";
    private static final String RESUME = """
            Ada Lovelace
            ada@example.com | Austin, TX
            Staff ML Engineer, Example Bank, 2019 to now
            Owned fraud scoring in production: 40M decisions a day.
            """;

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
        jdbc.update("DELETE FROM firm_setting WHERE name LIKE 'prospects.%' OR name LIKE 'ai.%'");
    }

    @Test
    void readsAResumeIntoANewCandidateWithoutSavingIt() throws Exception {
        long existing = created(Map.of("name", "Ada Lovelace", "email", "ada@example.com"));
        long candidates = count();

        // No key yet: it says who can add one, and nothing goes to an AI.
        mockMvc.perform(send(post("/api/candidates/read-resume").param("name", "ada.txt"), null)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(RESUME.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("Reading the resume needs a Claude API key")))
                .andExpect(jsonPath("$.messages[0]").value(containsString("Michael Owner can add one")));
        verify(claude, never()).messages(anyString(), anyString());

        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        claudeAnswers("""
                {"name": "Ada Lovelace", "email": "ada@example.com", "phone": "+1 512 555 0100",
                 "linkedinUrl": "linkedin.com/in/ada-example", "location": "Austin, TX",
                 "currentTitle": "Staff ML Engineer", "currentCompany": "Example Bank",
                 "track": "production_ai_engineering", "roleAreas": ["DEPLOY", "OPERATE", "Wizardry"],
                 "experience": "YEARS_8_9", "regulatedExperience": "yes",
                 "productionEvidence": "- Owned fraud scoring \\u2014 40M decisions a day"}
                """);

        mockMvc.perform(send(post("/api/candidates/read-resume").param("name", "ada.txt"), null)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(RESUME.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("ada.txt"))
                .andExpect(jsonPath("$.provider").value("Claude"))
                .andExpect(jsonPath("$.candidate.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.candidate.email").value("ada@example.com"))
                .andExpect(jsonPath("$.candidate.linkedinUrl").value("https://linkedin.com/in/ada-example"))
                .andExpect(jsonPath("$.candidate.track").value("PRODUCTION_AI_ENGINEERING"))
                .andExpect(jsonPath("$.candidate.roleAreas.length()").value(2))
                .andExpect(jsonPath("$.candidate.experience").value("YEARS_8_9"))
                .andExpect(jsonPath("$.candidate.regulatedExperience").value(true))
                .andExpect(jsonPath("$.candidate.productionEvidence").value("- Owned fraud scoring, 40M decisions a day"))
                .andExpect(jsonPath("$.candidate.status").doesNotExist())
                .andExpect(jsonPath("$.matches[0].id").value(existing))
                .andExpect(jsonPath("$.matches[0].why").value("same email"));

        JsonNode sent = sentToClaude();
        assertThat(sent.path("system").asText()).contains("Jane Michael AI Talent Search").contains("Never guess");
        assertThat(sent.path("messages").get(0).path("content").asText())
                .contains("Resume (ada.txt)").contains("Owned fraud scoring in production");
        assertThat(sent.path("tools").get(0).path("input_schema").path("required").toString())
                .contains("productionEvidence").contains("roleAreas");
        // A draft only: still one Ada.
        assertThat(count()).isEqualTo(candidates);
    }

    @Test
    void fillsInFromAResumeAlreadyOnTheCandidate() throws Exception {
        long id = created(Map.of("name", "Ada Lovelace"));
        String file = mockMvc.perform(send(post("/api/candidates/" + id + "/files").param("name", "ada.txt"), null)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(RESUME.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long fileId = json.readTree(file).path("id").asLong();
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        claudeAnswers("""
                {"name": "Ada Lovelace", "email": "not an email", "phone": "", "linkedinUrl": "", "location": "Austin, TX",
                 "currentTitle": "Staff ML Engineer", "currentCompany": "Example Bank", "track": "",
                 "roleAreas": [], "experience": "", "regulatedExperience": "no", "productionEvidence": ""}
                """);

        mockMvc.perform(send(post("/api/candidates/" + id + "/files/" + fileId + "/read"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("ada.txt"))
                .andExpect(jsonPath("$.candidate.currentTitle").value("Staff ML Engineer"))
                .andExpect(jsonPath("$.candidate.email").doesNotExist())
                .andExpect(jsonPath("$.candidate.regulatedExperience").value(false))
                // The candidate whose resume it is isn't a duplicate of themselves.
                .andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    void turnsCallNotesIntoAWriteUpANextStepAndChangesToCheck() throws Exception {
        long id = created(Map.of("name", "Grace Hopper", "location", "Austin, TX",
                "productionEvidence", "- Ran the model registry"));
        aiSettings.saveKey(AiProviderId.CLAUDE, CLAUDE_KEY);
        LocalDate today = LocalDate.now(clock);
        LocalDate friday = today.plusDays(2);
        claudeAnswers("""
                {"summary": "- Wants a staff role \\u2014 remote only.\\n- Interviewing at two banks.",
                 "status": "PASSIVE", "currentTitle": "", "currentCompany": "", "location": "austin, tx",
                 "workPreference": "remote", "willingToRelocate": "no", "employmentType": "",
                 "compExpectation": "$240k base plus equity", "addEvidence": "- Led the LLM eval rollout",
                 "nextStep": "Send the Example Bank job description", "nextStepDate": "%s", "nextStepType": "email"}
                """.formatted(friday));

        mockMvc.perform(send(post("/api/candidates/" + id + "/call-notes/read"),
                        Map.of("type", "CALL", "notes", "remote only. 240 + eq. 2 banks. send EB JD by fri")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.update.type").value("CALL"))
                .andExpect(jsonPath("$.update.summary").value("- Wants a staff role, remote only.\n"
                        + "- Interviewing at two banks.\nNext: Send the Example Bank job description"))
                .andExpect(jsonPath("$.update.status").value("PASSIVE"))
                .andExpect(jsonPath("$.update.workPreference").value("REMOTE"))
                .andExpect(jsonPath("$.update.compExpectation").value("$240k base plus equity"))
                .andExpect(jsonPath("$.update.addEvidence").value("- Led the LLM eval rollout"))
                .andExpect(jsonPath("$.update.nextActionDate").value(friday.toString()))
                .andExpect(jsonPath("$.update.nextActionType").value("EMAIL"))
                // Already what the record says, so not offered as changes.
                .andExpect(jsonPath("$.update.location").doesNotExist())
                .andExpect(jsonPath("$.update.willingToRelocate").doesNotExist())
                .andExpect(jsonPath("$.nextStep").value("Send the Example Bank job description"));

        String prompt = sentToClaude().path("messages").get(0).path("content").asText();
        assertThat(prompt).contains("Today is ").contains("Grace Hopper's record now:").contains("Location: Austin, TX")
                .contains("Notes from the call:").contains("send EB JD by fri");

        // Saved with the compensation unticked.
        Map<String, Object> save = new HashMap<>(Map.of(
                "type", "CALL",
                "summary", "- Wants a staff role, remote only.\nNext: Send the Example Bank job description",
                "status", "PASSIVE",
                "workPreference", "REMOTE",
                "addEvidence", "- Led the LLM eval rollout",
                "nextActionDate", friday.toString(),
                "nextActionType", "EMAIL"));
        mockMvc.perform(send(post("/api/candidates/" + id + "/call-notes"), save))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSIVE"))
                .andExpect(jsonPath("$.workPreference").value("REMOTE"))
                .andExpect(jsonPath("$.location").value("Austin, TX"))
                .andExpect(jsonPath("$.compExpectation").doesNotExist())
                .andExpect(jsonPath("$.productionEvidence").value("- Ran the model registry\n- Led the LLM eval rollout"))
                .andExpect(jsonPath("$.lastContactedDate").value(today.toString()))
                .andExpect(jsonPath("$.nextActionDate").value(friday.toString()))
                .andExpect(jsonPath("$.nextActionType").value("EMAIL"));

        JsonNode history = json.readTree(mockMvc.perform(send(get("/api/activities").param("candidateId",
                        String.valueOf(id)), null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<String> entries = history.findValuesAsText("summary");
        assertThat(entries).contains("Status changed from Active to Passive")
                .contains("- Wants a staff role, remote only.\nNext: Send the Example Bank job description");
        assertThat(history.findValuesAsText("type")).contains("CALL", "STATUS_CHANGE");

        save.put("type", "NOTE");
        mockMvc.perform(send(post("/api/candidates/" + id + "/call-notes"), save))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Save it as a call or a meeting."));
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM candidate", Long.class);
    }

    private void claudeAnswers(String input) {
        when(claude.messages(eq(CLAUDE_KEY), anyString())).thenReturn(new ClaudeApi.Reply(200, """
                {"stop_reason": "tool_use",
                 "content": [{"type": "tool_use", "id": "toolu_1", "name": "write", "input": %s}],
                 "usage": {"input_tokens": 2000, "output_tokens": 400}}
                """.formatted(input), null));
    }

    private JsonNode sentToClaude() throws Exception {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(claude).messages(eq(CLAUDE_KEY), sent.capture());
        return json.readTree(sent.getValue());
    }

    private long created(Map<String, Object> candidate) throws Exception {
        return json.readTree(mockMvc.perform(send(post("/api/candidates"), candidate))
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
        user.setPasswordHash(passwordEncoder.encode("cand-ai-test-password-1"));
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
