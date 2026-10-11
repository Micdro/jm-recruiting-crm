package com.janemichael.jmrecruitingcrm.importer;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactRow;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.DuplicateMode;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ImportResult;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.RowError;
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
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);

    private final ImportService importService = new ImportService(
            companyRepository, contactRepository, candidateRepository, currentUserService);

    private final AtomicLong ids = new AtomicLong(100);
    private final List<Company> existingCompanies = new ArrayList<>();
    private final List<Contact> existingContacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setDisplayName("Michael");
        when(currentUserService.get()).thenReturn(user);

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
                "Head of ML Platform", "priya@acme.ai", null, null, null, 2, null,
                LocalDate.of(2026, 10, 20), null, null);
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
        assertThat(first.getNextActionType()).isEqualTo(ActionType.EMAIL);
        assertThat(first.getCompany().getName()).isEqualTo("Acme AI");
        assertThat(saved.getAllValues().get(1).getCompany()).isSameAs(first.getCompany());
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
                null, null, null, null, "Met at Money20/20");

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
                null, null, null, OutreachStatus.IN_SEQUENCE, 9, null, null, null, null);

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

    private static ContactRow row(String company, String name, String email) {
        return new ContactRow(company, null, null, name, null, null, null, email, null, null,
                null, null, null, null, null, null);
    }
}
