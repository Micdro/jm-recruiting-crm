package com.janemichael.jmrecruitingcrm.importer;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.BuyingRole;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.CompanyImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.CompanyRow;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactImportRequest;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactRow;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.DuplicateMode;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ImportResult;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.RowError;
import com.janemichael.jmrecruitingcrm.undo.UndoLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportServiceTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final ContactRepository contactRepository = mock(ContactRepository.class);
    private final CandidateRepository candidateRepository = mock(CandidateRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);

    private final ImportService importService = new ImportService(
            companyRepository, contactRepository, candidateRepository, userRepository, currentUserService,
            mock(UndoLog.class));

    private final AppUser michael = user(7L, "Michael Drougas", "michael@jane-michael.com");
    private final AppUser dimitrios = user(8L, "Dimitrios Drougas", "dimitrios@jane-michael.com");
    private final AppUser jane = user(9L, "Jane Doe", "jane@jane-michael.com");

    private final AtomicLong ids = new AtomicLong(100);
    private final List<Company> existingCompanies = new ArrayList<>();
    private final List<Contact> existingContacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(currentUserService.get()).thenReturn(michael);
        when(userRepository.findAllByOrderByDisplayNameAsc()).thenReturn(List.of(dimitrios, jane, michael));

        when(companyRepository.findAll()).thenReturn(existingCompanies);
        when(contactRepository.findAllWithDetails()).thenReturn(existingContacts);
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            if (company.getId() == null) {
                company.setId(ids.incrementAndGet());
            }
            return company;
        });
        when(contactRepository.save(any(Contact.class))).thenAnswer(invocation -> {
            Contact contact = invocation.getArgument(0);
            if (contact.getId() == null) {
                contact.setId(ids.incrementAndGet());
            }
            return contact;
        });
    }

    @Test
    void createsMissingCompaniesOnceAndJoinsFirstAndLastNames() {
        ContactRow priya = new ContactRow("Acme AI", "https://acme.ai", null, null, "Priya", "Shah",
                "Head of ML Platform", "priya@acme.ai", null, null, null, BuyingRole.BUYER, 2, null,
                LocalDate.of(2026, 10, 20), null, null, "DD");
        ContactRow sam = row("acme ai", "Sam Lee", "sam@acme.ai");

        ImportResult result = importService.importContacts(
                new ContactImportRequest(List.of(priya, sam), DuplicateMode.SKIP));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.companiesCreated()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();

        ArgumentCaptor<Contact> saved = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository, times(2)).save(saved.capture());

        Contact first = saved.getAllValues().get(0);
        assertThat(first.getName()).isEqualTo("Priya Shah");
        assertThat(first.getStatus()).isEqualTo(OutreachStatus.IN_SEQUENCE);
        assertThat(first.getTouchCount()).isEqualTo(2);
        // Two touches sent, so the third is LinkedIn.
        assertThat(first.getNextActionType()).isEqualTo(ActionType.LINKEDIN);
        assertThat(first.getBuyingRole()).isEqualTo(BuyingRole.BUYER);
        assertThat(first.getOwner()).isSameAs(dimitrios);
        assertThat(first.getCompany().getName()).isEqualTo("Acme AI");
        assertThat(first.getCompany().getStatus()).isEqualTo(CompanyStatus.LEAD);
        assertThat(saved.getAllValues().get(1).getCompany()).isSameAs(first.getCompany());
        assertThat(saved.getAllValues().get(1).getOwner()).isSameAs(michael);
    }

    @Test
    void skipsDuplicatesAndReportsBadRowsWithSpreadsheetRowNumbers() {
        Company acme = new Company();
        acme.setId(1L);
        acme.setName("Acme AI");
        existingCompanies.add(acme);

        Contact jane = new Contact();
        jane.setId(2L);
        jane.setName("Jane Doe");
        jane.setEmail("jane@acme.ai");
        jane.setCompany(acme);
        existingContacts.add(jane);

        ImportResult result = importService.importContacts(new ContactImportRequest(List.of(
                row("Acme AI", "Jane Doe", "JANE@acme.ai"),
                row("Acme AI", null, "nobody@acme.ai"),
                row("Acme AI", "Bad Email", "not-an-email")
        ), null));

        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).containsExactly(
                new RowError(3, "Name is missing"),
                new RowError(4, "Email \"not-an-email\" isn't valid"));
        verify(contactRepository, never()).save(any(Contact.class));
    }

    @Test
    void updateModeFillsInTheExistingContact() {
        Company acme = new Company();
        acme.setId(1L);
        acme.setName("Acme AI");
        existingCompanies.add(acme);

        Contact jane = new Contact();
        jane.setId(2L);
        jane.setName("Jane Doe");
        jane.setTitle("Director");
        jane.setLinkedinUrl("https://www.linkedin.com/in/janedoe/");
        jane.setCompany(acme);
        existingContacts.add(jane);

        ContactRow update = new ContactRow("Acme AI", null, null, "Jane Doe", null, null,
                "VP Engineering", null, null, "linkedin.com/in/janedoe", OutreachStatus.REPLIED,
                null, null, null, null, null, "Met at Money20/20", null);

        ImportResult result = importService.importContacts(
                new ContactImportRequest(List.of(update), DuplicateMode.UPDATE));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(jane.getTitle()).isEqualTo("VP Engineering");
        assertThat(jane.getStatus()).isEqualTo(OutreachStatus.REPLIED);
        assertThat(jane.getNotes()).isEqualTo("Met at Money20/20");
    }

    @Test
    void fourImportedTouchesMeansTheSequenceIsOver() {
        ContactRow row = new ContactRow("Acme AI", null, null, "Done Sequence", null, null, null,
                null, null, null, OutreachStatus.IN_SEQUENCE, null, 9, null, null, null, null, null);

        importService.importContacts(new ContactImportRequest(List.of(row), DuplicateMode.SKIP));

        ArgumentCaptor<Contact> saved = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository).save(saved.capture());
        assertThat(saved.getValue().getTouchCount()).isEqualTo(4);
        assertThat(saved.getValue().getStatus()).isEqualTo(OutreachStatus.NO_RESPONSE);
    }

    @Test
    void fullNamePrefersTheNameColumn() {
        assertThat(ImportService.fullName(" Ana Ruiz ", "Ana", "R")).isEqualTo("Ana Ruiz");
        assertThat(ImportService.fullName(null, "Ana", null)).isEqualTo("Ana");
        assertThat(ImportService.fullName("", null, "Ruiz")).isEqualTo("Ruiz");
        assertThat(ImportService.fullName(null, " ", null)).isNull();
    }

    @Test
    void importsTheClickupClientListAndUpdatesExistingCompanies() {
        Company acme = new Company();
        acme.setId(1L);
        acme.setName("Acme AI");
        acme.setStatus(CompanyStatus.LEAD);
        acme.setNotes("First call went well.");
        existingCompanies.add(acme);

        CompanyRow update = companyRow("ACME AI", CompanyStatus.DISCOVERY_SCHEDULED, "Asked for two FDEs.", "MD");
        CompanyRow created = companyRow("Zest Risk", null, null, "Dimitrios");

        ImportResult result = importService.importCompanies(
                new CompanyImportRequest(List.of(update, created, companyRow(" ", null, null, null)), DuplicateMode.UPDATE));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errors()).containsExactly(new RowError(4, "Company name is missing"));

        assertThat(acme.getStatus()).isEqualTo(CompanyStatus.DISCOVERY_SCHEDULED);
        assertThat(acme.getNotes()).isEqualTo("First call went well.\n\nAsked for two FDEs.");
        assertThat(acme.getSegments()).isEqualTo("FRAUD_IDENTITY_RISK,COMPLIANCE_AML");
        assertThat(acme.getHiringTimeline()).isEqualTo(SearchEnums.Timeline.ONE_TO_TWO_MONTHS);
        assertThat(acme.getOwner()).isSameAs(michael);

        ArgumentCaptor<Company> saved = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Zest Risk");
        assertThat(saved.getValue().getStatus()).isEqualTo(CompanyStatus.LEAD);
        assertThat(saved.getValue().getOwner()).isSameAs(dimitrios);
    }

    @Test
    void ownersMatchByInitialsNamesAndEmails() {
        ImportService.OwnerMatcher owners = new ImportService.OwnerMatcher(List.of(michael, dimitrios, jane));

        assertThat(owners.match("MD", null)).isSameAs(michael);
        assertThat(owners.match("dd", null)).isSameAs(dimitrios);
        assertThat(owners.match("Jane", null)).isSameAs(jane);
        assertThat(owners.match("Dimitrios Drougas", null)).isSameAs(dimitrios);
        assertThat(owners.match("JANE@jane-michael.com", null)).isSameAs(jane);
        assertThat(owners.match("Jane Doe, Michael Drougas", null)).isSameAs(jane);
        assertThat(owners.match("Someone Else", michael)).isSameAs(michael);
        assertThat(owners.match(null, michael)).isSameAs(michael);
    }

    @Test
    void mergedNotesKeepBothAndStayWithinTheLimit() {
        assertThat(ImportService.mergeNotes(null, " New ", 100)).isEqualTo("New");
        assertThat(ImportService.mergeNotes("Old", "Old", 100)).isEqualTo("Old");
        assertThat(ImportService.mergeNotes("Old", "New", 100)).isEqualTo("Old\n\nNew");
        assertThat(ImportService.mergeNotes("12345", "67890", 8)).hasSize(8);
    }

    private static ContactRow row(String company, String name, String email) {
        return new ContactRow(company, null, null, name, null, null, null, email, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static CompanyRow companyRow(String name, CompanyStatus status, String notes, String owner) {
        return new CompanyRow(name, null, null, null, status, null, CompanyEnums.Industry.BANKS,
                List.of(CompanyEnums.Segment.COMPLIANCE_AML, CompanyEnums.Segment.FRAUD_IDENTITY_RISK),
                null, CompanyEnums.Priority.HIGH, CompanyEnums.HiringNeed.FDE,
                SearchEnums.Timeline.ONE_TO_TWO_MONTHS, 2, CompanyEnums.LeadSource.LINKEDIN,
                null, null, null, notes, owner);
    }

    private static AppUser user(Long id, String name, String email) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setDisplayName(name);
        user.setEmail(email);
        return user;
    }
}
