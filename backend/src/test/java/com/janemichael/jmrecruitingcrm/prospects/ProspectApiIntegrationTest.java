package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.TestSettings;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.sync.SyncIdentity;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The prospect finder against a real database, with the Claude API replaced by a stand-in so
 * nothing leaves the test. Runs are driven by hand (not on the background thread) so each test
 * rolls back.
 */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, "app.prospects.initial-delay-ms=86400000"})
@Transactional
class ProspectApiIntegrationTest {

    private static final String ADMIN = "prospects-admin@jane-michael.test";
    private static final String MEMBER = "prospects-member@jane-michael.test";
    private static final String KEY = "sk-ant-api03-test-key-1234";

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
    private ProspectFinder finder;

    @Autowired
    private ProspectResearch research;

    @Autowired
    private ProspectSettings settings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SyncIdentity identity;

    @MockitoBean
    private ClaudeApi api;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private Cookie csrf;
    private AppUser admin;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = account(ADMIN, "Prospects Admin", UserRole.ADMIN);
        account(MEMBER, "Prospects Member", UserRole.MEMBER);
        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        // Settings left by other test classes would change what these tests see.
        jdbc.update("DELETE FROM firm_setting WHERE name LIKE 'prospects.%'");
    }

    @Test
    void savingAKeyChecksItAndTurnsTheFinderOn() throws Exception {
        when(api.models(KEY)).thenReturn(new ClaudeApi.Reply(200, "{\"data\": []}", null));

        mockMvc.perform(send(ADMIN, "ADMIN", get("/api/prospects"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.perRun").value(10))
                .andExpect(jsonPath("$.monthlyLimitDollars").value(30));

        mockMvc.perform(send(ADMIN, "ADMIN", put("/api/prospects/key"), Map.of("apiKey", "  " + KEY + " ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.keyHint").value("1234"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.runsHere").value(true))
                .andExpect(jsonPath("$.running").value(false));

        // Stored encrypted, like the rest of the CRM.
        String stored = jdbc.queryForObject("SELECT value FROM firm_setting WHERE name = 'prospects.api_key'", String.class);
        assertThat(stored).isNotNull().doesNotContain("sk-ant");

        mockMvc.perform(send(ADMIN, "ADMIN", delete("/api/prospects/key"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void aKeyClaudeRejectsIsNotSaved() throws Exception {
        when(api.models(anyString())).thenReturn(new ClaudeApi.Reply(401, "{}", null));

        mockMvc.perform(send(ADMIN, "ADMIN", put("/api/prospects/key"), Map.of("apiKey", KEY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("didn't accept this key")));
        mockMvc.perform(send(ADMIN, "ADMIN", put("/api/prospects/key"), Map.of("apiKey", "hello")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(containsString("start with sk-ant-")));
        assertThat(settings.values().hasKey()).isFalse();
    }

    @Test
    void onlyAdminsChangeTheSettings() throws Exception {
        Map<String, Object> request = Map.of("enabled", true, "perRun", 5, "focus", "US only", "monthlyLimitDollars", 15);

        mockMvc.perform(send(MEMBER, "USER", put("/api/prospects/settings"), request))
                .andExpect(status().isForbidden());
        mockMvc.perform(send(MEMBER, "USER", get("/api/prospects"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(false));

        mockMvc.perform(send(ADMIN, "ADMIN", put("/api/prospects/settings"), request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perRun").value(5))
                .andExpect(jsonPath("$.focus").value("US only"))
                .andExpect(jsonPath("$.monthlyLimitDollars").value(15));

        mockMvc.perform(send(ADMIN, "ADMIN", put("/api/prospects/settings"),
                        Map.of("enabled", true, "perRun", 40, "monthlyLimitDollars", 15)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRunNeedsAKeyAndStaysWithinTheMonthlyLimit() throws Exception {
        mockMvc.perform(send(MEMBER, "USER", post("/api/prospects/run"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Add a Claude API key first."));

        settings.saveKey(KEY);
        settings.saveSchedule(true, 10, null, 0);
        mockMvc.perform(send(MEMBER, "USER", post("/api/prospects/run"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value(containsString("limit of $0 is reached")));
        assertThat(finder.isRunningHere()).isFalse();
    }

    @Test
    void foundCompaniesWaitForReviewAndStayOutOfTheQueue() throws Exception {
        Company known = new Company();
        known.setName("Ledgerlight AI");
        known.setWebsite("https://ledgerlight.example");
        companies.save(known);

        when(api.messages(anyString(), anyString())).thenReturn(new ClaudeApi.Reply(200, """
                {"stop_reason": "tool_use",
                 "content": [
                   {"type": "code_execution_tool_result", "tool_use_id": "srvtoolu_1",
                    "content": {"type": "code_execution_result",
                                "stdout": "Dana Ruiz, CTO https://www.linkedin.com/in/dana-ruiz-prospects-test dana.ruiz@northwind-prospects.example"}},
                   {"type": "tool_use", "id": "toolu_1", "name": "record_prospects", "input": {"companies": [
                     {"name": "Ledgerlight, Inc.", "website": "https://www.ledgerlight.example", "signal": "Hiring",
                      "sources": []},
                     {"name": "Different Name Same Site", "website": "ledgerlight.example/careers", "signal": "Hiring",
                      "sources": []},
                     {"name": "Northwind Prospects Test", "website": "https://northwind-prospects.example",
                      "location": "New York, US", "what_they_do": "Fraud models for credit unions.",
                      "industry": "BANKS", "segments": ["FRAUD_IDENTITY_RISK"], "hiring_need": "FDE",
                      "signal": "Series B of $30M in September 2026",
                      "sources": ["https://news.example/northwind-series-b"],
                      "people": [
                        {"name": "Dana Ruiz", "title": "CTO", "role": "BUYER",
                         "linkedin_url": "https://www.linkedin.com/in/dana-ruiz-prospects-test",
                         "email": "dana.ruiz@northwind-prospects.example"},
                        {"name": "Lee Park", "title": "VP Customer Engineering",
                         "linkedin_url": "https://www.linkedin.com/in/lee-park-made-up"}]}]}}],
                 "usage": {"input_tokens": 1000, "output_tokens": 100, "server_tool_use": {"web_search_requests": 3}}}
                """, null));

        ProspectResearch.Outcome outcome = research.run(new ProspectResearch.Request(KEY, 10, null,
                List.of("Ledgerlight AI"), LocalDate.now()));
        assertThat(outcome.found().withheld()).isEqualTo(1);
        ProspectFinder.Saved saved = finder.save(outcome.found().companies(), admin.getId());
        assertThat(saved.added()).isEqualTo(1);
        assertThat(saved.skipped()).isEqualTo(2);
        assertThat(saved.contacts()).isEqualTo(2);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(send(ADMIN, "ADMIN", get("/api/prospects/companies"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Northwind Prospects Test"))
                .andExpect(jsonPath("$[0].industry").value("BANKS"))
                .andExpect(jsonPath("$[0].segments[0]").value("FRAUD_IDENTITY_RISK"))
                .andExpect(jsonPath("$[0].notes").value(containsString("Why now: Series B of $30M in September 2026")))
                .andExpect(jsonPath("$[0].notes").value(containsString("https://news.example/northwind-series-b")))
                .andExpect(jsonPath("$[0].contacts.length()").value(2))
                .andExpect(jsonPath("$[0].contacts[0].linkedinUrl").value("https://www.linkedin.com/in/dana-ruiz-prospects-test"))
                .andExpect(jsonPath("$[0].contacts[0].email").value("dana.ruiz@northwind-prospects.example"))
                .andExpect(jsonPath("$[0].contacts[1].linkedinUrl").value(nullValue()));

        Company found = companies.findAll().stream()
                .filter(company -> "Northwind Prospects Test".equals(company.getName()))
                .findFirst().orElseThrow();
        assertThat(found.getStatus()).isEqualTo(CompanyStatus.AI_FOUND);
        assertThat(found.getOwner()).isNull();
        List<Long> people = contacts.findByCompanyId(found.getId()).stream().map(Contact::getId).toList();
        assertThat(contactIdsInQueue()).doesNotContainAnyElementsOf(people);

        mockMvc.perform(send(ADMIN, "ADMIN", get("/api/prospects/summary"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting").value(1));

        mockMvc.perform(send(ADMIN, "ADMIN", post("/api/prospects/companies/" + found.getId() + "/accept"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LEAD"));
        entityManager.flush();
        entityManager.clear();

        assertThat(companies.findById(found.getId()).orElseThrow().getStatus()).isEqualTo(CompanyStatus.LEAD);
        assertThat(contactIdsInQueue()).containsAll(people);
        mockMvc.perform(send(ADMIN, "ADMIN", post("/api/prospects/companies/" + found.getId() + "/accept"), null))
                .andExpect(status().isConflict());
    }

    @Test
    void dismissingParksTheCompanyAndTrashesItsUntouchedPeople() throws Exception {
        Found.Company prospect = new Found.Company("Contoso Dismiss Test", "https://contoso-dismiss.example", null,
                null, null, List.of(), null, "Hiring an FDE", List.of(), List.of(
                new Found.Person("Pat Lee", "CTO", null, null, null, null),
                new Found.Person("Kim Ode", "Head of Talent", null, null, null, null)));
        assertThat(finder.save(List.of(prospect), null).added()).isEqualTo(1);
        entityManager.flush();
        Company company = companies.findAll().stream()
                .filter(each -> "Contoso Dismiss Test".equals(each.getName()))
                .findFirst().orElseThrow();
        // Someone already wrote to Kim: she stays.
        Contact kim = contacts.findByCompanyId(company.getId()).stream()
                .filter(contact -> contact.getName().equals("Kim Ode")).findFirst().orElseThrow();
        kim.setStatus(OutreachStatus.IN_SEQUENCE);
        entityManager.flush();

        mockMvc.perform(send(ADMIN, "ADMIN", post("/api/prospects/companies/" + company.getId() + "/dismiss"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_QUALIFIED"))
                .andExpect(jsonPath("$.contactsRemoved").value(1));
        entityManager.flush();
        entityManager.clear();

        assertThat(companies.findById(company.getId()).orElseThrow().getStatus()).isEqualTo(CompanyStatus.NOT_QUALIFIED);
        assertThat(contacts.findByCompanyId(company.getId())).extracting(Contact::getName).containsExactly("Kim Ode");
        Integer trashed = jdbc.queryForObject("SELECT count(*) FROM trash_item WHERE kind = 'CONTACT'", Integer.class);
        assertThat(trashed).isPositive();

        // Dismissed companies are never suggested again.
        assertThat(finder.save(List.of(prospect), null).skipped()).isEqualTo(1);
    }

    @Test
    void runsOncePerWeekdayOnTheChosenComputer() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZonedDateTime thursday = ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, zone);
        int here = identity.nodeId();
        ProspectSettings.Values on = new ProspectSettings.Values(KEY, true, 10, null, here, 30);

        assertThat(finder.isDue(on, null, thursday)).isTrue();
        assertThat(finder.isDue(on, null, thursday.withHour(6))).isFalse();
        assertThat(finder.isDue(on, null, thursday.plusDays(2))).isFalse();
        assertThat(finder.isDue(new ProspectSettings.Values(KEY, false, 10, null, here, 30), null, thursday)).isFalse();
        assertThat(finder.isDue(new ProspectSettings.Values(null, true, 10, null, here, 30), null, thursday)).isFalse();
        assertThat(finder.isDue(new ProspectSettings.Values(KEY, true, 10, null, here + 1, 30), null, thursday)).isFalse();
        assertThat(finder.isDue(new ProspectSettings.Values(KEY, true, 10, null, null, 30), null, thursday)).isFalse();
        assertThat(finder.isDue(new ProspectSettings.Values(KEY, true, 10, null, here, 0), null, thursday)).isFalse();

        Instant earlierToday = thursday.withHour(7).withMinute(30).toInstant();
        ProspectSettings.Run today = ProspectSettings.Run.started("MANUAL", here, "Prospects Admin", earlierToday);
        assertThat(finder.isDue(on, today, thursday)).isFalse();
        ProspectSettings.Run yesterday = ProspectSettings.Run.started("SCHEDULED", here, null, earlierToday.minusSeconds(86_400));
        assertThat(finder.isDue(on, yesterday, thursday)).isTrue();
    }

    @Test
    void theAiFoundStageReadsWellInHistory() {
        assertThat(com.janemichael.jmrecruitingcrm.common.Labels.of(CompanyStatus.AI_FOUND)).isEqualTo("AI found");
    }

    // Helpers -----------------------------------------------------------------

    private List<Long> contactIdsInQueue() {
        return contacts.findUnscheduled(OutreachStatus.NEW, admin.getId()).stream().map(Contact::getId).toList();
    }

    private AppUser account(String email, String name, UserRole role) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setRole(role);
        user.setActive(true);
        user.setPasswordHash(passwordEncoder.encode("prospects-password-1"));
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
