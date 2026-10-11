package com.janemichael.jmrecruitingcrm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the API against a real PostgreSQL database (migrated by Flyway), with real security.
 * Each test rolls back, so nothing is left behind.
 */
@SpringBootTest
@Transactional
class ApiIntegrationTest {

    private static final String EMAIL = "integration-admin@jane-michael.test";
    private static final String PASSWORD = "integration-password-1";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    private MockMvc mockMvc;
    private Cookie csrf;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        AppUser admin = new AppUser();
        admin.setEmail(EMAIL);
        admin.setDisplayName("Integration Admin");
        admin.setRole(UserRole.ADMIN);
        admin.setActive(true);
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(admin);

        csrf = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(csrf).as("XSRF-TOKEN cookie").isNotNull();
    }

    // Security ----------------------------------------------------------------

    @Test
    void apiRequiresSignIn() throws Exception {
        mockMvc.perform(get("/api/companies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthCheckIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void changesWithoutTheCsrfTokenAreRejected() throws Exception {
        mockMvc.perform(post("/api/companies")
                        .with(user(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No Token Inc\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void signInStartsASession() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .param("email", EMAIL.toUpperCase())
                        .param("password", PASSWORD)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent())
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .param("email", EMAIL)
                        .param("password", "not-the-password")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messages[0]").value("Invalid email or password"));
    }

    @Test
    void adminsCanAddTeammates() throws Exception {
        mockMvc.perform(send(post("/api/users"), body(
                        "email", "jane@jane-michael.test",
                        "displayName", "Jane",
                        "role", "ADMIN",
                        "password", "a-long-password")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Jane"));

        mockMvc.perform(signedIn(get("/api/users")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'jane@jane-michael.test')]").exists());
    }

    // Recruiting flow -----------------------------------------------------------

    @Test
    void outreachSearchPipelineAndPlacementWorkEndToEnd() throws Exception {
        long companyId = id(mockMvc.perform(send(post("/api/companies"), body(
                        "name", "Ledgerlight AI",
                        "focus", "AI fraud detection for banks",
                        "status", "PROSPECTING")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerName").value("Integration Admin"))
                .andReturn());

        long contactId = id(mockMvc.perform(send(post("/api/contacts"), body(
                        "name", "Dana Reyes",
                        "title", "VP Engineering",
                        "email", "dana@ledgerlight.test",
                        "companyId", companyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andReturn());

        // Four touches, then the cap.
        for (int touch = 1; touch <= 3; touch++) {
            mockMvc.perform(send(post("/api/contacts/" + contactId + "/activities"), body("type", "EMAIL")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.touchCount").value(touch))
                    .andExpect(jsonPath("$.status").value("IN_SEQUENCE"));
        }
        mockMvc.perform(send(post("/api/contacts/" + contactId + "/activities"), body("type", "CALL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.touchCount").value(4))
                .andExpect(jsonPath("$.status").value("NO_RESPONSE"));
        mockMvc.perform(send(post("/api/contacts/" + contactId + "/activities"), body("type", "EMAIL")))
                .andExpect(status().isConflict());

        mockMvc.perform(send(post("/api/contacts/" + contactId + "/activities"), body("type", "REPLY",
                        "notes", "Interested in an FDE search")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLIED"));

        mockMvc.perform(signedIn(get("/api/activities").param("contactId", String.valueOf(contactId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].type").value("REPLY"));

        // A search makes the company a client.
        long searchId = id(mockMvc.perform(send(post("/api/searches"), body(
                        "companyId", companyId,
                        "hiringManagerId", contactId,
                        "title", "Forward Deployed Engineer",
                        "track", "PRODUCTION_AI_ENGINEERING",
                        "engagementType", "EXCLUSIVE",
                        "feePercent", 25,
                        "baseMin", 180000,
                        "baseMax", 230000,
                        "productionOutcome", "Live fraud models at two tier-1 banks")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INTAKE"))
                .andExpect(jsonPath("$.hiringManagerName").value("Dana Reyes"))
                .andReturn());

        mockMvc.perform(signedIn(get("/api/companies/" + companyId)))
                .andExpect(jsonPath("$.status").value("CLIENT"))
                .andExpect(jsonPath("$.openSearchCount").value(1));

        long candidateId = id(mockMvc.perform(send(post("/api/candidates"), body(
                        "name", "Avery Chen",
                        "currentTitle", "Staff ML Platform Engineer",
                        "track", "PRODUCTION_AI_ENGINEERING",
                        "regulatedExperience", true,
                        "productionEvidence", "Owned model serving for a card issuer")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn());

        long entryId = id(mockMvc.perform(send(post("/api/pipeline"), body(
                        "searchId", searchId,
                        "candidateId", candidateId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("SOURCED"))
                .andReturn());

        mockMvc.perform(send(post("/api/pipeline"), body("searchId", searchId, "candidateId", candidateId)))
                .andExpect(status().isConflict());

        mockMvc.perform(send(put("/api/pipeline/" + entryId), body(
                        "stage", "SHORTLISTED",
                        "rationale", "Ran production ML at a regulated issuer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("SHORTLISTED"));

        mockMvc.perform(signedIn(get("/api/searches/" + searchId)))
                .andExpect(jsonPath("$.stageCounts.SHORTLISTED").value(1));

        // The fee comes from the search terms; the guarantee defaults to 90 days.
        mockMvc.perform(send(post("/api/placements"), body(
                        "searchId", searchId,
                        "candidateId", candidateId,
                        "startDate", "2026-11-02",
                        "baseSalary", 200000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feeAmount").value(50000.0))
                .andExpect(jsonPath("$.guaranteeEndDate").value("2027-01-31"))
                .andExpect(jsonPath("$.invoiceStatus").value("NOT_INVOICED"));

        mockMvc.perform(signedIn(get("/api/candidates/" + candidateId + "/pipeline")))
                .andExpect(jsonPath("$[0].stage").value("PLACED"))
                .andExpect(jsonPath("$[0].placed").value(true));
        mockMvc.perform(signedIn(get("/api/candidates/" + candidateId)))
                .andExpect(jsonPath("$.status").value("PLACED"));

        mockMvc.perform(signedIn(get("/api/dashboard")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myTouchesToday").value(4))
                .andExpect(jsonPath("$.dailyTouchTarget").value(30))
                .andExpect(jsonPath("$.openSearches.length()").isNumber());

        // Records that others depend on can't be deleted out from under them.
        mockMvc.perform(signedIn(delete("/api/companies/" + companyId)))
                .andExpect(status().isConflict());
        mockMvc.perform(signedIn(delete("/api/candidates/" + candidateId)))
                .andExpect(status().isConflict());
    }

    @Test
    void fillQueuePullsNewContactsIntoToday() throws Exception {
        // Unassigned contacts already in a local database would be pulled first.
        String dashboard = mockMvc.perform(signedIn(get("/api/dashboard")))
                .andReturn().getResponse().getContentAsString();
        Number alreadyUnscheduled = JsonPath.read(dashboard, "$.unscheduledNewContacts");
        assumeTrue(alreadyUnscheduled.longValue() == 0, "Database already has unscheduled contacts");

        long companyId = id(mockMvc.perform(send(post("/api/companies"), body("name", "Queue Test Co")))
                .andExpect(status().isCreated())
                .andReturn());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(send(post("/api/contacts"), body("name", "Queue Person " + i, "companyId", companyId)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(send(post("/api/contacts/fill-queue"), body("count", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduled").value(2));

        mockMvc.perform(signedIn(get("/api/contacts").param("due", "true").param("companyId", String.valueOf(companyId))))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nextActionDate").value(LocalDate.now(java.time.ZoneId.of("America/New_York")).toString()));
    }

    @Test
    void importCreatesCompaniesAndSkipsDuplicates() throws Exception {
        Map<String, Object> request = body(
                "duplicateMode", "SKIP",
                "rows", List.of(
                        body("companyName", "Import Test Bank AI", "firstName", "Lee", "lastName", "Park",
                                "email", "lee@importtest.test", "touchCount", 1, "nextActionDate", "2026-12-01"),
                        body("companyName", "import test bank ai", "name", "Lee Park", "email", "LEE@importtest.test"),
                        body("companyName", "Import Test Bank AI", "email", "no-name@importtest.test")
                ));

        mockMvc.perform(send(post("/api/import/contacts"), request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.companiesCreated").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(4));

        mockMvc.perform(signedIn(get("/api/contacts").param("q", "lee@importtest.test")))
                .andExpect(jsonPath("$[0].name").value("Lee Park"))
                .andExpect(jsonPath("$[0].status").value("IN_SEQUENCE"))
                .andExpect(jsonPath("$[0].nextActionType").value("EMAIL"));
    }

    @Test
    void validationErrorsComeBackAsMessages() throws Exception {
        mockMvc.perform(send(post("/api/companies"), body("name", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Company name is required"));

        mockMvc.perform(send(post("/api/contacts"), body("name", "No Company")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(send(post("/api/searches"), body("companyId", 1, "title", "X", "track", "NOT_A_TRACK")))
                .andExpect(status().isBadRequest());
    }

    // Helpers -----------------------------------------------------------------

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request
                .with(user(EMAIL).roles("ADMIN"))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
    }

    private MockHttpServletRequestBuilder send(MockHttpServletRequestBuilder request, Object payload)
            throws Exception {
        return signedIn(request)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
    }

    private static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static long id(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }
}
