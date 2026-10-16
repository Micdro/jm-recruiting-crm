package com.janemichael.jmrecruitingcrm.signals;

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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hiring signals against a real database, with the job boards replaced by a stand-in so nothing
 * leaves the test. Each test rolls back.
 */
@SpringBootTest(properties = {TestSettings.ENCRYPTION_KEY, "app.signals.initial-delay-ms=86400000"})
@Transactional
class SignalApiIntegrationTest {

    private static final String EMAIL = "signals-admin@jane-michael.test";

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
    private SignalService signals;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JobBoardClient client;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private Cookie csrf;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        AppUser admin = new AppUser();
        admin.setEmail(EMAIL);
        admin.setDisplayName("Signals Admin");
        admin.setRole(UserRole.ADMIN);
        admin.setActive(true);
        admin.setPasswordHash(passwordEncoder.encode("signals-password-1"));
        users.save(admin);

        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    @Test
    void aPastedBoardLinkKeepsTheRolesTheFirmPlaces() throws Exception {
        Company acme = company("Acme Signals Test", "acme-signals.example");
        person(acme, "Dana Hiring");
        Instant now = Instant.now();
        when(client.fetch(any())).thenReturn(new JobPostings.Board("Acme Signals Test", List.of(
                posting("1", "Forward Deployed Engineer", now.minus(Duration.ofDays(2))),
                posting("2", "MLOps Engineer", now.minus(Duration.ofDays(60))),
                posting("3", "Account Executive", now.minus(Duration.ofDays(1))))));

        mockMvc.perform(send(put("/api/signals/companies/" + acme.getId() + "/board"),
                        Map.of("link", "https://job-boards.greenhouse.io/acmesignals/jobs/123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("GREENHOUSE"))
                .andExpect(jsonPath("$.token").value("acmesignals"))
                .andExpect(jsonPath("$.boardUrl").value("https://job-boards.greenhouse.io/acmesignals"))
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.openRoles").value(3))
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.roles[0].title").value("Forward Deployed Engineer"))
                .andExpect(jsonPath("$.roles[0].track").value("FDE"))
                .andExpect(jsonPath("$.roles[0].unreviewed").value(true))
                .andExpect(jsonPath("$.roles[1].track").value("AI_PLATFORM"))
                .andExpect(jsonPath("$.roles[1].unreviewed").value(false));

        // Stored encrypted, like everything else that identifies a company.
        List<String> titles = jdbc.queryForList(
                "SELECT title FROM job_posting WHERE company_id = ?", String.class, acme.getId());
        assertThat(titles).hasSize(2).allMatch(title -> title.startsWith("enc:"));

        mockMvc.perform(signedIn(get("/api/signals")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[?(@.companyId == " + acme.getId() + ")].unreviewedRoles").value(1))
                .andExpect(jsonPath("$.companies[?(@.companyId == " + acme.getId() + ")].people[0].name")
                        .value("Dana Hiring"));
        mockMvc.perform(signedIn(get("/api/signals/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.top[*].companyId").value(hasItem(acme.getId().intValue())))
                .andExpect(jsonPath("$.top[?(@.companyId == " + acme.getId() + ")].firstRole")
                        .value("Forward Deployed Engineer"));

        mockMvc.perform(signedIn(post("/api/signals/companies/" + acme.getId() + "/reviewed")))
                .andExpect(status().isNoContent());
        mockMvc.perform(signedIn(get("/api/signals/summary")))
                .andExpect(jsonPath("$.top[*].companyId").value(not(hasItem(acme.getId().intValue()))));
    }

    @Test
    void linksThatArentBoardsAreTurnedDown() throws Exception {
        Company acme = company("Acme Links Test", null);

        mockMvc.perform(send(put("/api/signals/companies/" + acme.getId() + "/board"),
                        Map.of("link", "https://acme.example/careers")))
                .andExpect(status().isBadRequest());

        when(client.fetch(any())).thenThrow(new JobBoardClient.BoardNotFoundException("No job board under that name"));
        mockMvc.perform(send(put("/api/signals/companies/" + acme.getId() + "/board"),
                        Map.of("link", "jobs.lever.co/no-such-board")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(org.hamcrest.Matchers.containsString("no Lever job board")));
    }

    @Test
    void theFinderSkipsAGreenhouseBoardForAnotherCompany() throws Exception {
        Company acme = company("Acme Finder AI", "https://www.acmefinder.example");
        when(client.fetch(any())).thenAnswer(invocation -> {
            JobBoard board = invocation.getArgument(0);
            if (board.token().equals("acmefinder") && board.provider() == JobBoardProvider.GREENHOUSE) {
                return new JobPostings.Board("Someone Else Entirely", List.of());
            }
            if (board.token().equals("acmefinder") && board.provider() == JobBoardProvider.ASHBY) {
                return new JobPostings.Board(null, List.of(posting("a1", "AI Platform Engineer", Instant.now())));
            }
            throw new JobBoardClient.BoardNotFoundException("No job board under that name");
        });

        signals.find(new SignalService.Target(acme.getId(), acme.getName(), acme.getWebsite(), null));

        Company found = companies.findById(acme.getId()).orElseThrow();
        assertThat(found.getJobBoardProvider()).isEqualTo(JobBoardProvider.ASHBY);
        assertThat(found.getJobBoardToken()).isEqualTo("acmefinder");
        assertThat(found.isJobBoardConfirmed()).isFalse();

        mockMvc.perform(signedIn(get("/api/signals/companies/" + acme.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].title").value("AI Platform Engineer"))
                .andExpect(jsonPath("$.searchedAt").isNotEmpty());

        mockMvc.perform(signedIn(post("/api/signals/companies/" + acme.getId() + "/confirm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));
    }

    @Test
    void aBoardThatHasGoneIsFlaggedAndItsRolesDropped() throws Exception {
        Company acme = company("Acme Gone Test", null);
        acme.setJobBoard(JobBoardProvider.LEVER, "acmegone", true);
        companies.save(acme);
        signals.store(acme.getId(), JobBoardProvider.LEVER,
                List.of(posting("l1", "Forward Deployed Engineer", Instant.now())));

        when(client.fetch(any())).thenThrow(new JobBoardClient.BoardNotFoundException("No job board under that name"));
        signals.check(new SignalService.Target(acme.getId(), acme.getName(), null,
                new JobBoard(JobBoardProvider.LEVER, "acmegone")));

        Integer left = jdbc.queryForObject(
                "SELECT count(*) FROM job_posting WHERE company_id = ?", Integer.class, acme.getId());
        assertThat(left).isZero();
        mockMvc.perform(signedIn(get("/api/signals")))
                .andExpect(jsonPath("$.failing[*].companyId").value(hasItem(acme.getId().intValue())));
    }

    @Test
    void noBoardKeepsTheFinderAway() throws Exception {
        Company acme = company("Acme None Test", null);

        mockMvc.perform(send(put("/api/signals/companies/" + acme.getId() + "/board"), Map.of("none", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("NONE"))
                .andExpect(jsonPath("$.boardUrl").isEmpty());

        mockMvc.perform(signedIn(get("/api/signals")))
                .andExpect(jsonPath("$.withoutBoard[*].companyId").value(not(hasItem(acme.getId().intValue()))));

        // Clearing it lets the finder look again.
        mockMvc.perform(send(put("/api/signals/companies/" + acme.getId() + "/board"), Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").isEmpty());
        mockMvc.perform(signedIn(get("/api/signals")))
                .andExpect(jsonPath("$.withoutBoard[*].companyId").value(hasItem(acme.getId().intValue())));
    }

    @Test
    void newCompaniesAreLookedUpOnTheirOwnExceptOnesThatArentAFit() {
        Company lead = company("Acme Auto Find Lead", "acme-auto.example");
        Company waiting = company("Acme Auto Find Waiting", null);
        waiting.setStatus(CompanyStatus.AI_FOUND);
        Company dismissed = company("Acme Auto Find Dismissed", null);
        dismissed.setStatus(CompanyStatus.NOT_QUALIFIED);
        Company searchedLongAgo = company("Acme Auto Find Old", null);
        Company searchedRecently = company("Acme Auto Find Recent", null);
        Company withBoard = company("Acme Auto Find Board", null);
        withBoard.setJobBoard(JobBoardProvider.GREENHOUSE, "acmeautofind", true);
        companies.flush();
        Instant now = Instant.now();
        searched(searchedLongAgo, now.minus(Duration.ofDays(45)));
        searched(searchedRecently, now.minus(Duration.ofDays(2)));

        List<Long> automatic = signals.findTargets(true).stream().map(SignalService.Target::companyId).toList();
        assertThat(automatic).contains(lead.getId(), searchedLongAgo.getId())
                .doesNotContain(waiting.getId(), dismissed.getId(), searchedRecently.getId(), withBoard.getId());
        // Never looked up comes before looked up a while ago.
        assertThat(automatic.indexOf(lead.getId())).isLessThan(automatic.indexOf(searchedLongAgo.getId()));
        assertThat(automatic).hasSizeLessThanOrEqualTo(SignalService.AUTO_FIND_BATCH);

        // Find job boards still covers companies marked not a fit, but never ones waiting on AI Found.
        List<Long> byHand = signals.findTargets(false).stream().map(SignalService.Target::companyId).toList();
        assertThat(byHand).contains(lead.getId(), dismissed.getId()).doesNotContain(waiting.getId());
    }

    @Test
    void companiesWaitingOnAiFoundArentListedAsMissingABoard() throws Exception {
        Company waiting = company("Acme Waiting Review Test", null);
        waiting.setStatus(CompanyStatus.AI_FOUND);
        companies.flush();

        mockMvc.perform(signedIn(get("/api/signals")))
                .andExpect(jsonPath("$.withoutBoard[*].companyId").value(not(hasItem(waiting.getId().intValue()))));
    }

    private void searched(Company company, Instant when) {
        jdbc.update("INSERT INTO job_board_check (company_id, searched_at) VALUES (?, ?)",
                company.getId(), java.sql.Timestamp.from(when));
    }

    private Company company(String name, String website) {
        Company company = new Company();
        company.setName(name);
        company.setWebsite(website);
        return companies.save(company);
    }

    private void person(Company company, String name) {
        Contact contact = new Contact();
        contact.setName(name);
        contact.setTitle("VP Engineering");
        contact.setCompany(company);
        contacts.save(contact);
    }

    private static JobPostings.Posting posting(String id, String title, Instant publishedAt) {
        return new JobPostings.Posting(id, title, "New York, NY", "https://example.test/jobs/" + id, publishedAt);
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request
                .with(user(EMAIL).roles("ADMIN"))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
    }

    private MockHttpServletRequestBuilder send(MockHttpServletRequestBuilder request, Object payload) throws Exception {
        return signedIn(request)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
    }
}
