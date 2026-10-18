package com.janemichael.jmrecruitingcrm.undo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.TestSettings;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recent changes against a real database. An action is a transaction, so unlike the other tests
 * this one isn't wrapped in one: each request commits, and what the test made is cleared out after.
 */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, TestSettings.NO_SYNC_TIMER,
        "app.undo.initial-delay-ms=86400000", "app.prospects.initial-delay-ms=86400000",
        "app.backups.initial-delay-ms=86400000"})
class UndoIntegrationTest {

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
    private UndoLog undoLog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FieldEncryptor encryptor;

    private final ObjectMapper json = new ObjectMapper();
    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private final List<Long> madeCompanies = new ArrayList<>();
    private TransactionTemplate transaction;
    private MockMvc mockMvc;
    private Cookie csrf;
    private long startTx;
    private AppUser me;
    private AppUser other;

    @BeforeEach
    void setUp() throws Exception {
        startTx = jdbc.queryForObject("SELECT pg_current_xact_id()::text::bigint", Long.class);
        transaction = new TransactionTemplate(transactionManager);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        me = account("undo-me-" + tag + "@jane-michael.test", "Undo Me");
        other = account("undo-other-" + tag + "@jane-michael.test", "Undo Other");
        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    @AfterEach
    void clearOut() {
        List<Long> ids = List.of(me.getId(), other.getId());
        for (Long id : ids) {
            jdbc.update("DELETE FROM trash_item WHERE deleted_by_id = ?", id);
        }
        for (Long company : madeCompanies) {
            jdbc.update("DELETE FROM contact WHERE company_id = ?", company);
            jdbc.update("DELETE FROM company WHERE id = ?", company);
        }
        for (Long id : ids) {
            jdbc.update("DELETE FROM undo_action WHERE user_id = ?", id);
            jdbc.update("DELETE FROM app_user WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM firm_setting WHERE name = 'prospects.feedback'");
        // What the test changed leaves nothing for other tests to sync.
        jdbc.update("DELETE FROM undo_row WHERE txid > ?", startTx);
        jdbc.update("DELETE FROM sync_row WHERE txid > ?", startTx);
    }

    @Test
    void anEditCanBeUndoneAndThenTheAddToo() throws Exception {
        long id = company("Undo Acme " + tag);
        perform(me, put("/api/companies/" + id), Map.of("name", "Undo Acme Renamed " + tag,
                "website", "https://acme-" + tag + ".test", "ownerId", me.getId()))
                .andExpect(status().isOk());

        JsonNode list = list(me);
        assertThat(list.get(0).get("label").asText()).isEqualTo("Edited Undo Acme Renamed " + tag);
        assertThat(list.get(0).get("kind").asText()).isEqualTo("COMPANY");
        assertThat(list.get(0).get("recordId").asLong()).isEqualTo(id);
        assertThat(list.get(1).get("label").asText()).isEqualTo("Added Undo Acme " + tag);
        long edit = list.get(0).get("id").asLong();
        long add = list.get(1).get("id").asLong();

        perform(me, post("/api/recent-changes/" + edit + "/undo"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.already").value(false));
        perform(me, get("/api/companies/" + id), null)
                .andExpect(jsonPath("$.name").value("Undo Acme " + tag))
                .andExpect(jsonPath("$.website").doesNotExist());
        assertThat(list(me).get(0).get("undoneAt").isNull()).isFalse();

        perform(me, post("/api/recent-changes/" + edit + "/undo"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value("That's already undone."));

        perform(me, post("/api/recent-changes/" + add + "/undo"), null).andExpect(status().isOk());
        perform(me, get("/api/companies/" + id), null).andExpect(status().isNotFound());
    }

    @Test
    void aDeleteComesBackWithItsHistoryAndLeavesTheTrash() throws Exception {
        long company = company("Undo Delete Co " + tag);
        long dana = contact(company, "Dana Undo " + tag);
        perform(me, post("/api/contacts/" + dana + "/activities"), Map.of("type", "NOTE", "notes", "Met at the summit"))
                .andExpect(status().isOk());
        int history = count("SELECT count(*) FROM activity WHERE contact_id = ?", dana);
        assertThat(history).isPositive();

        perform(me, delete("/api/contacts/" + dana), null).andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM trash_item WHERE kind = 'CONTACT' AND record_id = ?", dana)).isOne();
        JsonNode deleted = list(me).get(0);
        assertThat(deleted.get("label").asText()).isEqualTo("Deleted Dana Undo " + tag);

        perform(me, post("/api/recent-changes/" + deleted.get("id").asLong() + "/undo"), null)
                .andExpect(status().isOk());
        perform(me, get("/api/contacts/" + dana), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dana Undo " + tag));
        assertThat(count("SELECT count(*) FROM activity WHERE contact_id = ?", dana)).isEqualTo(history);
        assertThat(count("SELECT count(*) FROM trash_item WHERE kind = 'CONTACT' AND record_id = ?", dana)).isZero();
    }

    @Test
    void somethingChangedSinceStopsTheUndoUntilThatIsUndoneFirst() throws Exception {
        long id = company("Undo Twice Co " + tag);
        perform(me, put("/api/companies/" + id), Map.of("name", "Undo Twice Co " + tag, "location", "Boston",
                "ownerId", me.getId())).andExpect(status().isOk());
        perform(me, put("/api/companies/" + id), Map.of("name", "Undo Twice Co " + tag, "location", "Chicago",
                "ownerId", me.getId())).andExpect(status().isOk());
        JsonNode list = list(me);
        long second = list.get(0).get("id").asLong();
        long first = list.get(1).get("id").asLong();

        perform(me, post("/api/recent-changes/" + first + "/undo"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value(UndoLog.CHANGED_SINCE));
        perform(me, get("/api/companies/" + id), null).andExpect(jsonPath("$.location").value("Chicago"));

        perform(me, post("/api/recent-changes/" + second + "/undo"), null).andExpect(status().isOk());
        perform(me, post("/api/recent-changes/" + first + "/undo"), null).andExpect(status().isOk());
        perform(me, get("/api/companies/" + id), null).andExpect(jsonPath("$.location").doesNotExist());
    }

    @Test
    void anAddCantBeUndoneOnceSomethingNewHangsOffIt() throws Exception {
        long company = company("Undo Parent Co " + tag);
        long sam = contact(company, "Sam Undo " + tag);
        long added = list(me).get(0).get("id").asLong();
        // Someone else adds a note to the new contact, here or on another computer.
        transaction.executeWithoutResult(status -> jdbc.update(
                "INSERT INTO activity (contact_id, type, summary, occurred_at) VALUES (?, 'NOTE', 'Later note', now())", sam));

        perform(me, post("/api/recent-changes/" + added + "/undo"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value(UndoLog.CHANGED_SINCE));
        assertThat(count("SELECT count(*) FROM contact WHERE id = ?", sam)).isOne();
    }

    @Test
    void aMergeComesApart() throws Exception {
        long company = company("Undo Merge Co " + tag);
        long kept = contact(company, "Riley Kept " + tag);
        long merged = contact(company, "Riley Merged " + tag);
        perform(me, put("/api/contacts/" + merged), Map.of("name", "Riley Merged " + tag, "companyId", company,
                "title", "Head of AI", "ownerId", me.getId())).andExpect(status().isOk());

        perform(me, post("/api/merge"), Map.of("kind", "CONTACT", "keepId", kept, "mergeId", merged))
                .andExpect(status().isOk());
        perform(me, get("/api/contacts/" + kept), null).andExpect(jsonPath("$.title").value("Head of AI"));
        JsonNode merge = list(me).get(0);
        assertThat(merge.get("label").asText()).isEqualTo("Merged Riley Merged " + tag + " into Riley Kept " + tag);

        perform(me, post("/api/recent-changes/" + merge.get("id").asLong() + "/undo"), null)
                .andExpect(status().isOk());
        perform(me, get("/api/contacts/" + merged), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Head of AI"));
        perform(me, get("/api/contacts/" + kept), null).andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    void aDismissedAiFoundCompanyComesBackForReviewWithItsPeople() throws Exception {
        Company found = new Company();
        found.setName("Undo Found Co " + tag);
        found.setStatus(CompanyStatus.AI_FOUND);
        long id = companies.save(found).getId();
        madeCompanies.add(id);
        Contact person = new Contact();
        person.setName("Jo Found " + tag);
        person.setCompany(found);
        person.setStatus(OutreachStatus.NEW);
        long jo = contacts.save(person).getId();

        perform(me, post("/api/prospects/companies/" + id + "/dismiss"), Map.of("reason", "NOT_A_VENDOR"))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM contact WHERE id = ?", jo)).isZero();
        JsonNode dismissed = list(me).get(0);
        assertThat(dismissed.get("label").asText()).isEqualTo("Dismissed Undo Found Co " + tag + " from AI found");

        perform(me, post("/api/recent-changes/" + dismissed.get("id").asLong() + "/undo"), null)
                .andExpect(status().isOk());
        assertThat(companies.findById(id).orElseThrow().getStatus()).isEqualTo(CompanyStatus.AI_FOUND);
        assertThat(count("SELECT count(*) FROM contact WHERE id = ?", jo)).isOne();
    }

    @Test
    void eachPersonSeesAndUndoesOnlyTheirOwn() throws Exception {
        company("Undo Mine Co " + tag);
        long mine = list(me).get(0).get("id").asLong();

        assertThat(list(other)).isEmpty();
        perform(other, post("/api/recent-changes/" + mine + "/undo"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages[0]", containsString("isn't in your recent changes")));
    }

    @Test
    void changesCopiedInFromAnotherComputerArentRecorded() {
        long id = transaction.execute(status -> {
            Company company = new Company();
            company.setName("Undo Copied Co " + tag);
            return companies.save(company).getId();
        });
        madeCompanies.add(id);
        long txid = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('jmcrm.applying_row', ?, true)", String.class, "company:" + id);
            jdbc.update("UPDATE company SET location = 'Remote' WHERE id = ?", id);
            return jdbc.queryForObject("SELECT pg_current_xact_id()::text::bigint", Long.class);
        });
        assertThat(count("SELECT count(*) FROM undo_row WHERE txid = ?", txid)).isZero();
    }

    @Test
    void oldChangesAndRowsNobodyClaimedAreCleared() {
        jdbc.update("INSERT INTO undo_action (txid, user_id, label, kind, created_at) "
                + "VALUES (?, ?, 'old', 'OTHER', now() - interval '2 days')", startTx - 5, me.getId());
        jdbc.update("INSERT INTO undo_row (txid, table_name, row_id, created_at) "
                + "VALUES (?, 'company', 1, now() - interval '2 days')", startTx - 5);
        jdbc.update("INSERT INTO undo_row (txid, table_name, row_id, created_at) "
                + "VALUES (?, 'company', 1, now() - interval '20 minutes')", startTx - 6);
        jdbc.update("INSERT INTO undo_row (txid, table_name, row_id, created_at) VALUES (?, 'company', 1, now())",
                startTx - 7);

        undoLog.prune();

        assertThat(count("SELECT count(*) FROM undo_action WHERE txid = ?", startTx - 5)).isZero();
        assertThat(count("SELECT count(*) FROM undo_row WHERE txid IN (?, ?)", startTx - 5, startTx - 6)).isZero();
        assertThat(count("SELECT count(*) FROM undo_row WHERE txid = ?", startTx - 7)).isOne();
        jdbc.update("DELETE FROM undo_row WHERE txid = ?", startTx - 7);
    }

    @Test
    void encryptedFieldsCompareByWhatTheySay() {
        String name = "\"name\": \"" + encryptor.encrypt("Acme") + "\"";
        String again = "\"name\": \"" + encryptor.encrypt("Acme") + "\"";
        assertThat(name).isNotEqualTo(again);
        assertThat(undoLog.same("{" + name + ", \"updated_at\": \"a\"}", "{" + again + ", \"updated_at\": \"b\"}"))
                .isTrue();
        assertThat(undoLog.same("{" + name + "}", "{\"name\": \"" + encryptor.encrypt("Other") + "\"}")).isFalse();
        assertThat(undoLog.same("{\"status\": \"LEAD\"}", "{\"status\": \"PLACING\"}")).isFalse();
    }

    // Helpers -----------------------------------------------------------------

    private long company(String name) throws Exception {
        long id = id(perform(me, post("/api/companies"), Map.of("name", name))
                .andExpect(status().isCreated())
                .andReturn());
        madeCompanies.add(id);
        return id;
    }

    private long contact(long company, String name) throws Exception {
        return id(perform(me, post("/api/contacts"), Map.of("name", name, "companyId", company))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private JsonNode list(AppUser who) throws Exception {
        return json.readTree(perform(who, get("/api/recent-changes"), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions perform(AppUser who, MockHttpServletRequestBuilder request, Map<String, Object> payload)
            throws Exception {
        MockHttpServletRequestBuilder signedIn = request
                .with(user(who.getEmail()).roles("ADMIN"))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
        if (payload != null) {
            signedIn = signedIn.contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new LinkedHashMap<>(payload)));
        }
        return mockMvc.perform(signedIn);
    }

    private long id(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private AppUser account(String email, String name) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setRole(UserRole.ADMIN);
        user.setActive(true);
        user.setPasswordHash(passwordEncoder.encode("undo-password-1"));
        return users.save(user);
    }
}
