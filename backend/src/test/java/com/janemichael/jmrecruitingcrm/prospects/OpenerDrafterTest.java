package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.TestSettings;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Drafting messages with Claude, against a real database and a stand-in Claude API. */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, "app.prospects.initial-delay-ms=86400000",
        "app.signals.initial-delay-ms=86400000"})
@Transactional
class OpenerDrafterTest {

    private static final String EMAIL = "drafter-member@jane-michael.test";
    private static final String KEY = "sk-ant-api03-drafter-test-5678";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ContactRepository contacts;

    @Autowired
    private ProspectSettings settings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @MockitoBean
    private ClaudeApi api;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private Cookie csrf;
    private Contact dana;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        AppUser member = new AppUser();
        member.setEmail(EMAIL);
        member.setDisplayName("Michael Drafter");
        member.setRole(UserRole.MEMBER);
        member.setActive(true);
        member.setPasswordHash(passwordEncoder.encode("drafter-password-1"));
        users.save(member);
        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        jdbc.update("DELETE FROM firm_setting WHERE name LIKE 'prospects.%'");

        Company company = new Company();
        company.setName("Northwind Drafter Test");
        company.setFocus("Fraud models for credit unions.");
        company.setHiringNeed(CompanyEnums.HiringNeed.FDE);
        company.setSegments("FRAUD_IDENTITY_RISK");
        company.setNotes("Found by the prospect finder on October 16, 2026.\n\nWhy now: Series B of $30M in "
                + "September 2026 to expand into regional banks.\n\nSources:\nhttps://news.example/northwind");
        companies.save(company);
        dana = new Contact();
        dana.setName("Dana Ruiz");
        dana.setTitle("CTO");
        dana.setCompany(company);
        dana.setStatus(OutreachStatus.NEW);
        contacts.save(dana);
    }

    private static ClaudeApi.Reply wrote(String body, String subject, int inputTokens) {
        String input = subject == null
                ? "{\"body\": " + quote(body) + "}"
                : "{\"subject\": " + quote(subject) + ", \"body\": " + quote(body) + "}";
        return new ClaudeApi.Reply(200, """
                {"stop_reason": "tool_use",
                 "content": [{"type": "tool_use", "id": "toolu_1", "name": "write_message", "input": %s}],
                 "usage": {"input_tokens": %d, "output_tokens": 150}}
                """.formatted(input, inputTokens), null);
    }

    private static String quote(String text) {
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void draftsAConnectionNoteFromWhyNowAndCountsItsCost() throws Exception {
        settings.saveKey(KEY);
        when(api.messages(eq(KEY), anyString())).thenReturn(wrote(
                "Hi Dana — congrats on the Series B. We place forward deployed engineers who have shipped AI at "
                        + "banks. Open to connecting?", null, 2_000));

        mockMvc.perform(send(post("/api/prospects/draft"), Map.of("contactId", dana.getId(), "channel", "LINKEDIN",
                        "touch", 1, "example", "Hi Dana, I run Jane Michael AI Talent Search.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Hi Dana, congrats on the Series B. We place forward deployed "
                        + "engineers who have shipped AI at banks. Open to connecting?"))
                .andExpect(jsonPath("$.subject").value(nullValue()))
                .andExpect(jsonPath("$.basis[0]").value(containsString("Why now: Series B of $30M")))
                .andExpect(jsonPath("$.costCents").value(1));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(api).messages(eq(KEY), sent.capture());
        JsonNode request = json.readTree(sent.getValue());
        assertThat(request.path("tool_choice").path("name").asText()).isEqualTo("write_message");
        assertThat(request.path("tools").get(0).path("input_schema").path("properties").has("subject")).isFalse();
        String message = request.path("messages").get(0).path("content").asText();
        assertThat(message).contains("LinkedIn connection request note, under 270 characters")
                .contains("Why now: Series B of $30M").contains("Recipient's first name: Dana")
                .contains("Sender: Michael").contains("fraud, identity and risk")
                .contains("forward deployed engineers").contains("I run Jane Michael AI Talent Search");
        assertThat(request.path("system").asText()).contains("No em dashes");

        assertThat(settings.spentCents(LocalDate.now(clock))).isEqualTo(1);
    }

    @Test
    void asksOnceMoreWhenAConnectionNoteIsTooLong() throws Exception {
        settings.saveKey(KEY);
        when(api.messages(eq(KEY), anyString()))
                .thenReturn(wrote("x".repeat(340), null, 2_000))
                .thenReturn(wrote("Hi Dana, congrats on the Series B. Open to connecting?", null, 2_500));

        mockMvc.perform(send(post("/api/prospects/draft"),
                        Map.of("contactId", dana.getId(), "channel", "LINKEDIN", "touch", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Hi Dana, congrats on the Series B. Open to connecting?"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(api, times(2)).messages(eq(KEY), sent.capture());
        assertThat(json.readTree(sent.getAllValues().get(1)).path("messages").get(0).path("content").asText())
                .contains("This draft is 340 characters, over LinkedIn's limit of 300");
    }

    @Test
    void emailsGetASubjectAndAnOpenRole() throws Exception {
        settings.saveKey(KEY);
        when(api.messages(eq(KEY), anyString())).thenReturn(wrote("Hi Dana,\n\nI saw the opening.\n\nMichael",
                "Your forward deployed engineer opening", 2_000));

        mockMvc.perform(send(post("/api/prospects/draft"), Map.of("contactId", dana.getId(), "channel", "EMAIL",
                        "touch", 2, "role", "Forward Deployed Engineer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Your forward deployed engineer opening"))
                .andExpect(jsonPath("$.basis[1]").value("Open role: Forward Deployed Engineer"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(api).messages(eq(KEY), sent.capture());
        JsonNode request = json.readTree(sent.getValue());
        assertThat(request.path("tools").get(0).path("input_schema").path("properties").has("subject")).isTrue();
        assertThat(request.path("messages").get(0).path("content").asText())
                .contains("an email with a subject line").contains("The open role this message is about: Forward Deployed Engineer");
    }

    @Test
    void needsAKeyAndRoomInTheLimits() throws Exception {
        mockMvc.perform(send(post("/api/prospects/draft"),
                        Map.of("contactId", dana.getId(), "channel", "LINKEDIN", "touch", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("needs a Claude API key")));

        settings.saveKey(KEY);
        settings.saveLimits(1, 30);
        settings.addSpend(LocalDate.now(clock), 98);
        mockMvc.perform(send(post("/api/prospects/draft"),
                        Map.of("contactId", dana.getId(), "channel", "LINKEDIN", "touch", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value(containsString("Today's AI limit of $1 is used up")));

        mockMvc.perform(send(post("/api/prospects/draft"),
                        Map.of("contactId", dana.getId(), "channel", "FAX", "touch", 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writesPlainText() {
        assertThat(OpenerDrafter.clean("Hi Dana — quick note – thanks.\n\n\n\nMichael  "))
                .isEqualTo("Hi Dana, quick note, thanks.\n\nMichael");
        assertThat(OpenerDrafter.whyNow("Found.\n\nWhy now: Hiring FDEs.\n\nSources:\nhttps://a.example"))
                .isEqualTo("Hiring FDEs.");
        assertThat(OpenerDrafter.whyNow("Met at a conference.")).isNull();
        assertThat(OpenerDrafter.firstName("  Dana  Ruiz ")).isEqualTo("Dana");
    }

    @Test
    void sizesEachStepOfTheSequence() {
        assertThat(OpenerDrafter.kind(facts("LINKEDIN", 1, true))).contains("connection request note");
        assertThat(OpenerDrafter.kind(facts("LINKEDIN", 2, true))).contains("accepted the connection");
        assertThat(OpenerDrafter.kind(facts("LINKEDIN", 3, true))).contains("follow-up");
        assertThat(OpenerDrafter.kind(facts("LINKEDIN", 4, true))).contains("closes the loop");
        assertThat(OpenerDrafter.kind(facts("LINKEDIN", 1, false))).isEqualTo("a short LinkedIn message, under 80 words");
        assertThat(OpenerDrafter.kind(facts("EMAIL", 1, true))).contains("email");
    }

    private static OpenerDrafter.Facts facts(String channel, Integer touch, boolean cold) {
        return new OpenerDrafter.Facts("Michael", "Dana", "Dana Ruiz", "CTO", null, "Northwind", null, null, null,
                null, null, List.of(), null, channel, touch, cold, null);
    }

    private MockHttpServletRequestBuilder send(MockHttpServletRequestBuilder request, Object payload) throws Exception {
        return request
                .with(user(EMAIL).roles("MEMBER"))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
    }
}
