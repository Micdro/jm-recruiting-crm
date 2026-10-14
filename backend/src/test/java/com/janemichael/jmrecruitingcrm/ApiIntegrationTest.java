package com.janemichael.jmrecruitingcrm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.common.EncryptionBackfill;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.KeyWrap;
import com.janemichael.jmrecruitingcrm.desktop.MorningReminder;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the API against a real PostgreSQL database (migrated by Flyway), with real security.
 * Each test rolls back, so nothing is left behind.
 */
@SpringBootTest(properties = TestSettings.ENCRYPTION_KEY)
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

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EncryptionBackfill encryptionBackfill;

    @Autowired
    private FieldEncryptor encryptor;

    @Autowired
    private MorningReminder morningReminder;

    @Autowired
    private Clock clock;

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
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.crmLocked").value(false));
    }

    @Test
    void signingInSavesTheAccountsOwnLockedCopyOfTheKeyAndUnlocksALockedCrm() throws Exception {
        String key = encryptor.exportKey();
        mockMvc.perform(login(EMAIL, PASSWORD, "198.51.100.30")).andExpect(status().isNoContent());

        String wrapped = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().getKeyWrap();
        assertThat(wrapped).startsWith("kw1:").doesNotContain(key.substring(0, 20));
        assertThat(KeyWrap.unwrap(wrapped, PASSWORD)).isEqualTo(key);

        encryptor.lock();
        try {
            mockMvc.perform(signedIn(get("/api/companies")))
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.error").value("Locked"));
            mockMvc.perform(signedIn(get("/api/auth/me")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.crmLocked").value(true));
            mockMvc.perform(get("/api/health")).andExpect(status().isOk());

            // The password opens this person's copy, which unlocks the CRM.
            mockMvc.perform(login(EMAIL, PASSWORD, "198.51.100.30")).andExpect(status().isNoContent());
            assertThat(encryptor.isConfigured()).isTrue();
            mockMvc.perform(signedIn(get("/api/companies"))).andExpect(status().isOk());
        } finally {
            if (!encryptor.isConfigured()) {
                encryptor.configure(key);
            }
        }
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
    void repeatedWrongPasswordsLockTheAccountForAWhile() throws Exception {
        String email = "throttle-test@jane-michael.test";
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(login(email, "wrong-" + attempt, "203.0.113.50"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login(email, "wrong-6", "203.0.113.50"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.messages[0]").value(containsString("Too many failed sign-in attempts")));
    }

    @Test
    void passwordsAreHashedWithArgon2AndOldBcryptHashesAreUpgradedAtSignIn() throws Exception {
        assertThat(userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().getPasswordHash())
                .startsWith("{argon2}");

        AppUser legacy = new AppUser();
        legacy.setEmail("legacy@jane-michael.test");
        legacy.setDisplayName("Legacy User");
        legacy.setRole(UserRole.MEMBER);
        legacy.setActive(true);
        legacy.setPasswordHash("{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD));
        userRepository.save(legacy);

        mockMvc.perform(login("legacy@jane-michael.test", PASSWORD, "198.51.100.20"))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findByEmailIgnoreCase("legacy@jane-michael.test").orElseThrow().getPasswordHash())
                .startsWith("{argon2}");
    }

    @Test
    void deactivatedUsersAreSignedOutOnTheirNextRequest() throws Exception {
        AppUser former = new AppUser();
        former.setEmail("former@jane-michael.test");
        former.setDisplayName("Former Partner");
        former.setRole(UserRole.MEMBER);
        former.setActive(false);
        former.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(former);

        mockMvc.perform(get("/api/companies").with(user("former@jane-michael.test")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messages[0]").value("Your account is no longer active"));
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/health").secure(true))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));

        mockMvc.perform(signedIn(get("/api/companies")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void sensitiveFieldsAreEncryptedInTheDatabase() throws Exception {
        long companyId = id(mockMvc.perform(send(post("/api/companies"), body(
                        "name", "Cipher Test Co",
                        "notes", "Budget approved for two FDE hires")))
                .andExpect(status().isCreated())
                .andReturn());

        long contactId = id(mockMvc.perform(send(post("/api/contacts"), body(
                        "name", "Morgan Lee",
                        "title", "Platform Lead",
                        "email", "morgan@ciphertest.test",
                        "phone", "+1 212 555 0101",
                        "notes", "Prefers LinkedIn",
                        "companyId", companyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("morgan@ciphertest.test"))
                .andReturn());

        entityManager.flush();

        Map<String, Object> row = jdbc.queryForMap(
                "select email, phone, notes, email_hash from contact where id = ?", contactId);
        assertThat((String) row.get("email")).startsWith("enc:v1:").doesNotContain("morgan");
        assertThat((String) row.get("phone")).startsWith("enc:v1:").doesNotContain("555");
        assertThat((String) row.get("notes")).startsWith("enc:v1:").doesNotContain("LinkedIn");
        assertThat((String) row.get("email_hash")).isNotBlank().doesNotContain("morgan");

        String companyNotes = jdbc.queryForObject("select notes from company where id = ?", String.class, companyId);
        assertThat(companyNotes).startsWith("enc:v1:").doesNotContain("Budget");

        // Names, titles and money too: nothing that identifies anyone is stored readable.
        Map<String, Object> company = jdbc.queryForMap("select name, name_hash from company where id = ?", companyId);
        assertThat((String) company.get("name")).startsWith("enc:v1:").doesNotContain("Cipher");
        assertThat(company.get("name_hash")).isEqualTo(encryptor.blindIndex("cipher test co"));
        Map<String, Object> person = jdbc.queryForMap("select name, title from contact where id = ?", contactId);
        assertThat((String) person.get("name")).startsWith("enc:v1:").doesNotContain("Morgan");
        assertThat((String) person.get("title")).startsWith("enc:v1:").doesNotContain("Platform");

        long searchId = id(mockMvc.perform(send(post("/api/searches"), body(
                        "companyId", companyId,
                        "title", "Head of Applied AI",
                        "track", "PRODUCTION_AI_ENGINEERING",
                        "location", "New York",
                        "baseMin", 210000,
                        "baseMax", 260000)))
                .andExpect(status().isCreated())
                .andReturn());
        entityManager.flush();
        Map<String, Object> search = jdbc.queryForMap(
                "select title, location, base_min, base_max from job_search where id = ?", searchId);
        assertThat((String) search.get("title")).startsWith("enc:v1:").doesNotContain("Applied");
        assertThat((String) search.get("location")).startsWith("enc:v1:").doesNotContain("York");
        assertThat((String) search.get("base_min")).startsWith("enc:v1:").doesNotContain("210");
        assertThat((String) search.get("base_max")).startsWith("enc:v1:").doesNotContain("260");
        mockMvc.perform(signedIn(get("/api/searches/" + searchId)))
                .andExpect(jsonPath("$.title").value("Head of Applied AI"))
                .andExpect(jsonPath("$.baseMin").value(210000));

        // Companies are still matched by name, ignoring case.
        mockMvc.perform(send(post("/api/companies"), body("name", "CIPHER TEST CO")))
                .andExpect(status().isConflict());
        mockMvc.perform(signedIn(get("/api/companies").param("q", "cipher")))
                .andExpect(jsonPath("$[0].name").value("Cipher Test Co"));

        // Reading back through the app decrypts, and duplicates are still caught.
        mockMvc.perform(signedIn(get("/api/contacts/" + contactId)))
                .andExpect(jsonPath("$.phone").value("+1 212 555 0101"))
                .andExpect(jsonPath("$.notes").value("Prefers LinkedIn"));
        mockMvc.perform(send(post("/api/contacts"), body(
                        "name", "Morgan Again",
                        "email", "MORGAN@ciphertest.test",
                        "companyId", companyId)))
                .andExpect(status().isConflict());
    }

    @Test
    void plainTextRowsFromBeforeEncryptionAreEncryptedAtStartup() throws Exception {
        long companyId = id(mockMvc.perform(send(post("/api/companies"), body("name", "Legacy Rows Co")))
                .andExpect(status().isCreated())
                .andReturn());
        long contactId = id(mockMvc.perform(send(post("/api/contacts"), body(
                        "name", "Riley Legacy", "companyId", companyId)))
                .andExpect(status().isCreated())
                .andReturn());
        entityManager.flush();

        // As an older version of the app would have stored them.
        jdbc.update("update contact set email = ?, notes = ?, email_hash = null where id = ?",
                "riley@legacy.test", "Plain notes", contactId);
        entityManager.clear();

        encryptionBackfill.run(null);

        Map<String, Object> row = jdbc.queryForMap("select email, notes, email_hash from contact where id = ?", contactId);
        assertThat((String) row.get("email")).startsWith("enc:v1:");
        assertThat((String) row.get("notes")).startsWith("enc:v1:");
        assertThat(row.get("email_hash")).isEqualTo(encryptor.blindIndex("riley@legacy.test"));

        mockMvc.perform(signedIn(get("/api/contacts/" + contactId)))
                .andExpect(jsonPath("$.email").value("riley@legacy.test"))
                .andExpect(jsonPath("$.notes").value("Plain notes"));
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

        // Their own copy of the key, locked with the password they were given.
        String wrapped = userRepository.findByEmailIgnoreCase("jane@jane-michael.test").orElseThrow().getKeyWrap();
        assertThat(KeyWrap.unwrap(wrapped, "a-long-password")).isEqualTo(encryptor.exportKey());

        mockMvc.perform(send(post("/api/users"), body(
                        "email", "short@jane-michael.test",
                        "displayName", "Short",
                        "role", "MEMBER",
                        "password", "elevenchars")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Password must be at least 12 characters"));
    }

    @Test
    void messageTemplatesAreSharedEncryptedAndChecked() throws Exception {
        long id = id(mockMvc.perform(send(post("/api/templates"), body(
                        "name", "Intro email",
                        "channel", "EMAIL",
                        "touch", 2,
                        "subject", "Engineers for {company}",
                        "body", "Hi {first name}, are you hiring deployment engineers?  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerName").value("Integration Admin"))
                .andExpect(jsonPath("$.body").value("Hi {first name}, are you hiring deployment engineers?"))
                .andReturn());
        entityManager.flush();

        Map<String, Object> row = jdbc.queryForMap("select name, subject, body from message_template where id = ?", id);
        assertThat((String) row.get("name")).startsWith("enc:v1:").doesNotContain("Intro");
        assertThat((String) row.get("subject")).startsWith("enc:v1:").doesNotContain("Engineers");
        assertThat((String) row.get("body")).startsWith("enc:v1:").doesNotContain("hiring");

        // A LinkedIn message has no subject.
        mockMvc.perform(send(put("/api/templates/" + id), body(
                        "name", "Connection note", "channel", "LINKEDIN", "touch", 1,
                        "subject", "Not used", "body", "Hi {first name}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("LINKEDIN"))
                .andExpect(jsonPath("$.subject").value(nullValue()));
        mockMvc.perform(signedIn(get("/api/templates")))
                .andExpect(jsonPath("$[?(@.name == 'Connection note')]").exists());

        mockMvc.perform(send(post("/api/templates"), body("name", "Call script", "channel", "CALL", "body", "Hello")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Templates are for LinkedIn or email"));
        mockMvc.perform(send(post("/api/templates"), body("name", "Five", "channel", "EMAIL", "touch", 5, "body", "Hi")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Touch must be 1 to 4"));

        mockMvc.perform(signedIn(delete("/api/templates/" + id))).andExpect(status().isNoContent());
        mockMvc.perform(send(put("/api/templates/" + id), body("name", "Gone", "channel", "EMAIL", "body", "Hi")))
                .andExpect(status().isNotFound());
    }

    @Test
    void weeklyNumbersCountTouchesRepliesAndWhichTouchGotThem() throws Exception {
        long companyId = id(mockMvc.perform(send(post("/api/companies"), body("name", "Northwind Robotics")))
                .andExpect(status().isCreated())
                .andReturn());
        long dana = id(mockMvc.perform(send(post("/api/contacts"), body("name", "Dana Reyes", "companyId", companyId)))
                .andExpect(status().isCreated())
                .andReturn());
        long sam = id(mockMvc.perform(send(post("/api/contacts"), body("name", "Sam Ortiz", "companyId", companyId)))
                .andExpect(status().isCreated())
                .andReturn());

        // Dana gets touches 1 and 2, replies to the email, and takes a meeting. Sam gets touch 1.
        for (String type : List.of("LINKEDIN", "EMAIL", "REPLY", "MEETING")) {
            mockMvc.perform(send(post("/api/contacts/" + dana + "/activities"), body("type", type)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(send(post("/api/contacts/" + sam + "/activities"), body("type", "LINKEDIN")))
                .andExpect(status().isOk());
        entityManager.flush();

        mockMvc.perform(signedIn(get("/api/reports/weekly")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyTarget").value(150))
                .andExpect(jsonPath("$.weeks.length()").value(12))
                .andExpect(jsonPath("$.weeks[11].linkedin").value(2))
                .andExpect(jsonPath("$.weeks[11].email").value(1))
                .andExpect(jsonPath("$.weeks[11].replies").value(1))
                .andExpect(jsonPath("$.weeks[11].meetings").value(1))
                .andExpect(jsonPath("$.weeks[11].started").value(2))
                .andExpect(jsonPath("$.byTouch[0].touches").value(2))
                .andExpect(jsonPath("$.byTouch[0].replies").value(0))
                .andExpect(jsonPath("$.byTouch[1].touches").value(1))
                .andExpect(jsonPath("$.byTouch[1].replies").value(1))
                .andExpect(jsonPath("$.byChannel[?(@.channel == 'EMAIL')].replies").value(1))
                .andExpect(jsonPath("$.people").isEmpty());

        mockMvc.perform(signedIn(get("/api/reports/weekly").param("scope", "team")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyTarget").value(nullValue()))
                .andExpect(jsonPath("$.people[?(@.name == 'Integration Admin')].touches").value(3))
                .andExpect(jsonPath("$.people[?(@.name == 'Integration Admin')].replies").value(1));
    }

    @Test
    void theMorningReminderCountsWhatIsDueForThePersonOnThisComputer() throws Exception {
        long companyId = id(mockMvc.perform(send(post("/api/companies"), body("name", "Harbor Ledger")))
                .andExpect(status().isCreated())
                .andReturn());
        String today = LocalDate.now(clock).toString();
        mockMvc.perform(send(post("/api/contacts"), body("name", "Ava Chen", "companyId", companyId, "nextActionDate", today)))
                .andExpect(status().isCreated());
        long ben = id(mockMvc.perform(send(post("/api/contacts"), body("name", "Ben Ortiz", "companyId", companyId)))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(send(post("/api/contacts/" + ben + "/activities"), body("type", "REPLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLIED"));
        entityManager.flush();

        MorningReminder.Shown reminder = morningReminder.showNow(EMAIL);

        // Not a Windows desktop here, so it isn't shown, but it says what it would.
        assertThat(reminder.shown()).isFalse();
        assertThat(reminder.message().title()).isEqualTo("Good morning. 2 contacts are due today.");
        assertThat(reminder.message().body()).startsWith("1 sequence touch and 1 reply to answer.");
        assertThat(morningReminder.showNow("nobody@jane-michael.test").message()).isNull();
    }

    @Test
    void exportSendsFullRecordsForTheListsAskedFor() throws Exception {
        long companyId = id(mockMvc.perform(send(post("/api/companies"), body("name", "Quillstone Risk")))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(send(post("/api/contacts"), body(
                        "name", "Rhea Patel", "companyId", companyId, "notes", "Met at the fraud summit")))
                .andExpect(status().isCreated());
        entityManager.flush();

        mockMvc.perform(signedIn(get("/api/export").param("include", "contacts,companies")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contacts[?(@.name == 'Rhea Patel')].notes").value("Met at the fraud summit"))
                .andExpect(jsonPath("$.companies[?(@.name == 'Quillstone Risk')].contactCount").value(1))
                .andExpect(jsonPath("$.activities").value(nullValue()));

        mockMvc.perform(signedIn(get("/api/export")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipeline").isArray())
                .andExpect(jsonPath("$.activities").isArray());

        mockMvc.perform(signedIn(get("/api/export").param("include", "passwords")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void candidateFilesAreStoredEncryptedAndServedBack() throws Exception {
        long candidateId = id(mockMvc.perform(send(post("/api/candidates"), body("name", "Priya Natarajan")))
                .andExpect(status().isCreated())
                .andReturn());
        byte[] resume = "%PDF-1.7\nPriya Natarajan, Staff ML Platform Engineer".getBytes(StandardCharsets.UTF_8);
        String files = "/api/candidates/" + candidateId + "/files";

        long fileId = id(mockMvc.perform(signedIn(post(files)).param("name", "C:\\Users\\me\\Priya Resume.pdf")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(resume))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("Priya Resume.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(resume.length))
                .andExpect(jsonPath("$.available").value(true))
                .andReturn());
        entityManager.flush();

        // Nothing readable in the database: not the name, not the contents.
        Map<String, Object> row = jdbc.queryForMap("SELECT file_name, blob_key FROM candidate_file WHERE id = ?", fileId);
        assertThat((String) row.get("file_name")).startsWith("enc:v1:").doesNotContain("Priya");
        byte[] stored = jdbc.queryForObject("SELECT content FROM file_blob WHERE blob_key = ?", byte[].class, row.get("blob_key"));
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).startsWith("JMB1").doesNotContain("Priya");

        mockMvc.perform(signedIn(get(files)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("Priya Resume.pdf"))
                .andExpect(jsonPath("$[0].uploadedByName").value("Integration Admin"));
        mockMvc.perform(signedIn(get(files + "/" + fileId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(resume));
        mockMvc.perform(signedIn(get(files + "/" + fileId)).param("download", "true"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));

        mockMvc.perform(signedIn(post(files)).param("name", "setup.exe")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {'M', 'Z'}))
                .andExpect(status().isBadRequest());
        mockMvc.perform(signedIn(post(files)).param("name", "fake.pdf")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("hello".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("That file isn't a real .pdf file"));
        mockMvc.perform(signedIn(post(files)).param("name", "huge.txt")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[10 * 1024 * 1024 + 1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("Files can be up to 10 MB"));

        mockMvc.perform(signedIn(delete(files + "/" + fileId))).andExpect(status().isNoContent());
        mockMvc.perform(signedIn(get(files + "/" + fileId))).andExpect(status().isNotFound());
        // The contents are kept while the file is in the Trash, so it can be restored.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_blob WHERE blob_key = ?", Long.class, row.get("blob_key")))
                .isEqualTo(1L);
        mockMvc.perform(signedIn(get("/api/trash")))
                .andExpect(jsonPath("$[0].kind").value("FILE"))
                .andExpect(jsonPath("$[0].label").value("Priya Resume.pdf"));
    }

    @Test
    void deletedRecordsWaitInTheTrashWithTheirHistoryAndComeBack() throws Exception {
        long companyId = id(mockMvc.perform(send(post("/api/companies"), body("name", "Harbor Trust")))
                .andExpect(status().isCreated()).andReturn());
        long nina = id(mockMvc.perform(send(post("/api/contacts"), body("name", "Nina Park", "title", "CTO", "companyId", companyId)))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(send(post("/api/contacts/" + nina + "/activities"), body("type", "LINKEDIN", "notes", "Asked about FDEs")))
                .andExpect(status().isOk());
        long searchId = id(mockMvc.perform(send(post("/api/searches"), body(
                        "companyId", companyId, "title", "Forward Deployed Engineer",
                        "track", "PRODUCTION_AI_ENGINEERING", "hiringManagerId", nina)))
                .andExpect(status().isCreated()).andReturn());
        entityManager.flush();

        mockMvc.perform(signedIn(delete("/api/contacts/" + nina))).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(signedIn(get("/api/contacts/" + nina))).andExpect(status().isNotFound());
        mockMvc.perform(signedIn(get("/api/searches/" + searchId))).andExpect(jsonPath("$.hiringManagerId").value(nullValue()));
        mockMvc.perform(signedIn(get("/api/trash")))
                .andExpect(jsonPath("$[0].kind").value("CONTACT"))
                .andExpect(jsonPath("$[0].recordId").value(nina))
                .andExpect(jsonPath("$[0].label").value("Nina Park"))
                .andExpect(jsonPath("$[0].detail").value(containsString("CTO at Harbor Trust")))
                .andExpect(jsonPath("$[0].detail").value(containsString("history entr")))
                .andExpect(jsonPath("$[0].deletedByName").value("Integration Admin"));
        Map<String, Object> stored = jdbc.queryForMap("SELECT label, contents FROM trash_item WHERE record_id = ?", nina);
        assertThat((String) stored.get("label")).startsWith("enc:v1:").doesNotContain("Nina");
        assertThat((String) stored.get("contents")).startsWith("enc:v1:").doesNotContain("LINKEDIN");

        mockMvc.perform(signedIn(post("/api/trash/undo")).param("kind", "CONTACT").param("recordId", String.valueOf(nina)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Nina Park"))
                .andExpect(jsonPath("$.path").value("/contacts/" + nina));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(signedIn(get("/api/contacts/" + nina)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("CTO"))
                .andExpect(jsonPath("$.touchCount").value(1));
        mockMvc.perform(signedIn(get("/api/activities").param("contactId", String.valueOf(nina))))
                .andExpect(jsonPath("$[?(@.type == 'LINKEDIN')].summary").value(hasItem(containsString("Asked about FDEs"))));
        mockMvc.perform(signedIn(get("/api/searches/" + searchId))).andExpect(jsonPath("$.hiringManagerId").value(nina));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trash_item WHERE record_id = ?", Long.class, nina)).isZero();

        // A contact can't come back without its company; if that's in the Trash, it says so.
        long otherCompany = id(mockMvc.perform(send(post("/api/companies"), body("name", "Pier Analytics")))
                .andExpect(status().isCreated()).andReturn());
        long omar = id(mockMvc.perform(send(post("/api/contacts"), body("name", "Omar Haddad", "companyId", otherCompany)))
                .andExpect(status().isCreated()).andReturn());
        entityManager.flush();
        mockMvc.perform(signedIn(delete("/api/contacts/" + omar))).andExpect(status().isNoContent());
        mockMvc.perform(signedIn(delete("/api/companies/" + otherCompany))).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(signedIn(post("/api/trash/undo")).param("kind", "CONTACT").param("recordId", String.valueOf(omar)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value("Restore Pier Analytics first. It's in the Trash too."));
        mockMvc.perform(signedIn(post("/api/trash/undo")).param("kind", "COMPANY").param("recordId", String.valueOf(otherCompany)))
                .andExpect(status().isOk());
        mockMvc.perform(signedIn(post("/api/trash/undo")).param("kind", "CONTACT").param("recordId", String.valueOf(omar)))
                .andExpect(status().isOk());
    }

    @Test
    void aDeletedCandidateComesBackWithItsResume() throws Exception {
        long candidateId = id(mockMvc.perform(send(post("/api/candidates"), body("name", "Ines Duarte")))
                .andExpect(status().isCreated()).andReturn());
        byte[] resume = "%PDF-1.7\nInes Duarte".getBytes(StandardCharsets.UTF_8);
        String files = "/api/candidates/" + candidateId + "/files";
        long fileId = id(mockMvc.perform(signedIn(post(files)).param("name", "Ines.pdf")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(resume))
                .andExpect(status().isCreated()).andReturn());
        entityManager.flush();

        mockMvc.perform(signedIn(delete("/api/candidates/" + candidateId))).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(signedIn(get("/api/trash")))
                .andExpect(jsonPath("$[?(@.recordId == " + candidateId + ")].detail").value(hasItem(containsString("1 file"))));

        mockMvc.perform(signedIn(post("/api/trash/undo")).param("kind", "CANDIDATE").param("recordId", String.valueOf(candidateId)))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(signedIn(get(files + "/" + fileId)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(resume));
    }

    // Recruiting flow -----------------------------------------------------------

    @Test
    void outreachSearchPipelineAndPlacementWorkEndToEnd() throws Exception {
        long companyId = id(mockMvc.perform(send(post("/api/companies"), body(
                        "name", "Ledgerlight AI",
                        "focus", "AI fraud detection for banks",
                        "status", "INITIAL_OUTREACH",
                        "industry", "BANKS",
                        "segments", List.of("FRAUD_IDENTITY_RISK"),
                        "hiringNeed", "FDE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerName").value("Integration Admin"))
                .andExpect(jsonPath("$.segments[0]").value("FRAUD_IDENTITY_RISK"))
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

        // A search moves the company to Placing.
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
                .andExpect(jsonPath("$.status").value("PLACING"))
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
    void clientListImportCreatesCompaniesWithTheirFollowUps() throws Exception {
        Map<String, Object> request = body(
                "duplicateMode", "UPDATE",
                "rows", List.of(
                        body("name", "Import Client Co", "status", "DISCOVERY_SCHEDULED", "priority", "HIGH",
                                "hiringTimeline", "ASAP", "anticipatedHires", 2, "followUpDate", "2020-01-02",
                                "owner", "IA", "notes", "Long discovery memo"),
                        body("name", "", "status", "LEAD")
                ));

        mockMvc.perform(send(post("/api/import/companies"), request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(3));

        mockMvc.perform(signedIn(get("/api/companies").param("q", "Import Client Co")))
                .andExpect(jsonPath("$[0].status").value("DISCOVERY_SCHEDULED"))
                .andExpect(jsonPath("$[0].ownerName").value("Integration Admin"));

        mockMvc.perform(signedIn(get("/api/dashboard")))
                .andExpect(jsonPath("$.companiesDue[?(@.name == 'Import Client Co')]").exists());
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

    private MockHttpServletRequestBuilder login(String email, String password, String address) {
        return post("/api/auth/login")
                .param("email", email)
                .param("password", password)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                });
    }

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
