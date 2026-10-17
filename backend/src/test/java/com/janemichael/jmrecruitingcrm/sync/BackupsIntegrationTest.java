package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.TestSettings;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Backups and restores against a real database, kept in a temporary folder. */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, TestSettings.NO_SYNC_TIMER,
        "app.backups.initial-delay-ms=86400000"})
@Transactional
class BackupsIntegrationTest {

    private static final Path FOLDER;

    static {
        try {
            FOLDER = Files.createTempDirectory("jmcrm-backups-test");
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @DynamicPropertySource
    static void folder(DynamicPropertyRegistry registry) {
        registry.add("app.backups.folder", FOLDER::toString);
    }

    @Autowired
    private Backups backups;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ContactRepository contacts;

    @Autowired
    private CandidateRepository candidates;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private WebApplicationContext context;

    @BeforeEach
    void emptyFolder() throws IOException {
        try (Stream<Path> files = Files.list(FOLDER)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    @Test
    void restoringBringsBackWhatTheBackupHeldOnEveryComputer() throws Exception {
        Company kept = company("Backup Kept Co");
        Contact dana = contact(kept, "Dana Backup");
        Candidate avery = new Candidate();
        avery.setName("Avery Backup");
        candidates.save(avery);
        entityManager.flush();
        jdbc.update("INSERT INTO file_blob (blob_key, content) VALUES ('backuptestfile00000000000000001', ?)",
                (Object) "resume bytes".getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO candidate_file (candidate_id, file_name, content_type, size_bytes, blob_key)
                VALUES (?, 'resume.pdf', 'application/pdf', 12, 'backuptestfile00000000000000001')
                """, avery.getId());
        jdbc.update("INSERT INTO firm_setting (name, value) VALUES ('invoice.firm_name', 'Old Name LLC') "
                + "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value");
        jdbc.update("INSERT INTO firm_setting (name, value) VALUES ('prospects.daily_limit', '2') "
                + "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value");

        Backups.Entry entry = backups.backUp(Backups.Kind.MANUAL);
        assertThat(entry.kind()).isEqualTo(Backups.Kind.MANUAL);
        byte[] file = Files.readAllBytes(FOLDER.resolve(entry.name()));
        assertThat(Arrays.copyOf(file, 4)).isEqualTo("JMF1".getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(file, StandardCharsets.ISO_8859_1)).doesNotContain("Old Name LLC");

        // Things go wrong after the backup.
        kept.setName("Backup Kept Co Renamed");
        Company added = company("Backup Added Later Co");
        entityManager.flush();
        jdbc.update("DELETE FROM contact WHERE id = ?", dana.getId());
        jdbc.update("DELETE FROM candidate_file WHERE candidate_id = ?", avery.getId());
        jdbc.update("DELETE FROM file_blob WHERE blob_key = 'backuptestfile00000000000000001'");
        jdbc.update("UPDATE firm_setting SET value = 'New Name LLC' WHERE name = 'invoice.firm_name'");
        jdbc.update("UPDATE firm_setting SET value = '5' WHERE name = 'prospects.daily_limit'");
        entityManager.clear();

        Backups.Restored restored = backups.restore(entry.name());
        entityManager.clear();

        assertThat(restored.records()).isPositive();
        assertThat(restored.safetyBackup()).contains("-before-restore");
        assertThat(FOLDER.resolve(restored.safetyBackup())).exists();
        assertThat(companies.findById(kept.getId()).orElseThrow().getName()).isEqualTo("Backup Kept Co");
        assertThat(companies.findById(added.getId())).isEmpty();
        assertThat(contacts.findById(dana.getId())).map(Contact::getName).contains("Dana Backup");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_file WHERE candidate_id = ?", Integer.class,
                avery.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_blob WHERE blob_key = 'backuptestfile00000000000000001'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value FROM firm_setting WHERE name = 'invoice.firm_name'", String.class))
                .isEqualTo("Old Name LLC");
        // The finder's own settings stay as they are.
        assertThat(jdbc.queryForObject("SELECT value FROM firm_setting WHERE name = 'prospects.daily_limit'", String.class))
                .isEqualTo("5");

        // Each change is versioned, so it reaches the other computers.
        assertThat(jdbc.queryForObject("SELECT deleted FROM sync_row WHERE table_name = 'company' AND row_id = ?",
                Boolean.class, added.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted FROM sync_row WHERE table_name = 'contact' AND row_id = ?",
                Boolean.class, dana.getId())).isFalse();
    }

    @Test
    void aDamagedOrForeignFileIsntRestored() throws Exception {
        Path bogus = FOLDER.resolve("jmcrm-backup-2026-01-01-000000-manual.jmbak");
        Files.write(bogus, "not a backup".getBytes(StandardCharsets.UTF_8));
        Company company = company("Backup Untouched Co");
        entityManager.flush();

        assertThatThrownBy(() -> backups.restore(bogus.getFileName().toString()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("can't be read");
        assertThat(companies.findById(company.getId())).isPresent();
    }

    @Test
    void onlyBackupNamesAreAccepted() throws Exception {
        Files.write(FOLDER.resolve("notes.txt"), new byte[] {1});
        assertThatThrownBy(() -> backups.file("../notes.txt")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> backups.file("notes.txt")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> backups.file("jmcrm-backup-2026-01-01-000000-manual.jmbak"))
                .isInstanceOf(NotFoundException.class);
        assertThat(backups.list()).isEmpty();
    }

    @Test
    void keepsTheNewestTenAndTheLastSafetyBackup() throws Exception {
        Files.write(FOLDER.resolve("jmcrm-backup-2025-12-01-000000-before-restore.jmbak"), new byte[] {1});
        for (int day = 10; day < 22; day++) {
            Files.write(FOLDER.resolve("jmcrm-backup-2026-01-" + day + "-000000-weekly.jmbak"), new byte[] {1});
        }

        backups.backUp(Backups.Kind.MANUAL);

        List<Backups.Entry> left = backups.list();
        assertThat(left).hasSize(Backups.KEEP + 1);
        assertThat(left.getFirst().kind()).isEqualTo(Backups.Kind.MANUAL);
        assertThat(left).extracting(Backups.Entry::name)
                .contains("jmcrm-backup-2025-12-01-000000-before-restore.jmbak", "jmcrm-backup-2026-01-21-000000-weekly.jmbak")
                .doesNotContain("jmcrm-backup-2026-01-10-000000-weekly.jmbak");
    }

    @Test
    void anyoneCanBackUpButOnlyTheOwnerRestores() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Cookie csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        account("backup-owner@jane-michael.test", "Backup Owner", UserRole.ADMIN, true);
        account("backup-admin@jane-michael.test", "Backup Admin", UserRole.ADMIN, false);
        account("backup-member@jane-michael.test", "Backup Member", UserRole.MEMBER, false);

        mockMvc.perform(as("backup-member@jane-michael.test", "MEMBER", post("/api/backups"), csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.backups.length()").value(1))
                .andExpect(jsonPath("$.backups[0].kind").value("MANUAL"))
                .andExpect(jsonPath("$.canRestore").value(false))
                .andExpect(jsonPath("$.ownerName").value("Backup Owner"));
        String name = backups.list().getFirst().name();

        mockMvc.perform(as("backup-member@jane-michael.test", "MEMBER", get("/api/backups/" + name), csrf))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(name)));

        mockMvc.perform(as("backup-admin@jane-michael.test", "ADMIN", post("/api/backups/" + name + "/restore"), csrf))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messages[0]").value("Only the owner (Backup Owner) can restore a backup."));
        mockMvc.perform(as("backup-owner@jane-michael.test", "ADMIN", post("/api/backups/" + name + "/restore"), csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.safetyBackup").value(containsString("before-restore")));
    }

    private Company company(String name) {
        Company company = new Company();
        company.setName(name);
        return companies.save(company);
    }

    private Contact contact(Company company, String name) {
        Contact contact = new Contact();
        contact.setName(name);
        contact.setCompany(company);
        return contacts.save(contact);
    }

    private void account(String email, String name, UserRole role, boolean owner) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setRole(role);
        user.setActive(true);
        user.setAccountOwner(owner);
        user.setPasswordHash(passwordEncoder.encode("backup-password-1"));
        users.save(user);
    }

    private static MockHttpServletRequestBuilder as(String email, String role, MockHttpServletRequestBuilder request,
                                                    Cookie csrf) {
        return request.with(user(email).roles(role)).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }
}
