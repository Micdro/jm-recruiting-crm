package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.activity.LogActivityRequest;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The outbound rules: four touches per sequence (LinkedIn, email, LinkedIn, email), next touch
 * three business days out.
 */
class ContactServiceTest {

    /** Wednesday, October 14, 2026 in New York. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final ContactRepository contactRepository = mock(ContactRepository.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ActivityLogger activityLogger = mock(ActivityLogger.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/New_York"));
    private final FieldEncryptor encryptor = new FieldEncryptor("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

    private final ContactService contactService = new ContactService(
            contactRepository, companyRepository, currentUserService, activityLogger, encryptor, clock,
            mock(TrashService.class));

    private final AppUser user = new AppUser();

    @BeforeEach
    void setUp() {
        user.setId(7L);
        user.setEmail("michael@jane-michael.com");
        user.setDisplayName("Michael");
        user.setRole(UserRole.ADMIN);
        when(currentUserService.get()).thenReturn(user);
    }

    @Test
    void firstTouchStartsTheSequenceAndSchedulesTheNextTouchThreeBusinessDaysOut() {
        Contact contact = contact(OutreachStatus.NEW, 0);

        ContactResponse response = contactService.logActivity(1L, request(ActivityType.EMAIL));

        assertThat(response.status()).isEqualTo(OutreachStatus.IN_SEQUENCE);
        assertThat(response.touchCount()).isEqualTo(1);
        assertThat(response.lastContactedDate()).isEqualTo(TODAY);
        // Thursday, Friday, then Monday: weekends are skipped.
        assertThat(response.nextActionDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(response.nextActionType()).isEqualTo(ActionType.EMAIL);
        verify(activityLogger).forContact(contact, ActivityType.EMAIL, true, "Touch 1 of 4", user);
    }

    @Test
    void notesAreAddedToTheTouchSummary() {
        Contact contact = contact(OutreachStatus.IN_SEQUENCE, 1);

        contactService.logActivity(1L, new LogActivityRequest(
                ActivityType.LINKEDIN, "Sent connection note", null, null, false, null));

        verify(activityLogger).forContact(contact, ActivityType.LINKEDIN, true,
                "Touch 2 of 4: Sent connection note", user);
    }

    @Test
    void fourthTouchEndsTheSequence() {
        contact(OutreachStatus.IN_SEQUENCE, 3);

        ContactResponse response = contactService.logActivity(1L, request(ActivityType.CALL));

        assertThat(response.touchCount()).isEqualTo(4);
        assertThat(response.status()).isEqualTo(OutreachStatus.NO_RESPONSE);
        assertThat(response.nextActionDate()).isNull();
        assertThat(response.nextActionType()).isNull();
    }

    @Test
    void fifthTouchIsRejected() {
        contact(OutreachStatus.IN_SEQUENCE, 4);

        assertThatThrownBy(() -> contactService.logActivity(1L, request(ActivityType.EMAIL)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("4 touches");

        verifyNoInteractions(activityLogger);
    }

    @Test
    void outreachAfterTheSequenceEndsNeedsARestart() {
        contact(OutreachStatus.NO_RESPONSE, 4);

        assertThatThrownBy(() -> contactService.logActivity(1L, request(ActivityType.LINKEDIN)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Restart the sequence");
    }

    @Test
    void aReplyIsStillLoggedAfterTheSequenceEnds() {
        contact(OutreachStatus.NO_RESPONSE, 4);

        ContactResponse response = contactService.logActivity(1L, request(ActivityType.REPLY));

        assertThat(response.status()).isEqualTo(OutreachStatus.REPLIED);
    }

    @Test
    void doNotContactBlocksOutreach() {
        contact(OutreachStatus.DO_NOT_CONTACT, 0);

        assertThatThrownBy(() -> contactService.logActivity(1L, request(ActivityType.EMAIL)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void replyMovesTheContactToRepliedAndPutsAResponseOnTodaysList() {
        Contact contact = contact(OutreachStatus.IN_SEQUENCE, 2);

        ContactResponse response = contactService.logActivity(1L, request(ActivityType.REPLY));

        assertThat(response.status()).isEqualTo(OutreachStatus.REPLIED);
        assertThat(response.touchCount()).isEqualTo(2);
        assertThat(response.nextActionDate()).isEqualTo(TODAY);
        assertThat(response.nextActionType()).isEqualTo(ActionType.EMAIL);
        verify(activityLogger).forContact(eq(contact), eq(ActivityType.REPLY), eq(false), isNull(), eq(user));
    }

    @Test
    void outreachToAnEngagedContactCompletesTheDueActionWithoutCountingTowardTheSequence() {
        Contact contact = contact(OutreachStatus.ENGAGED, 0);
        contact.setNextActionDate(TODAY);
        contact.setNextActionType(ActionType.CALL);

        ContactResponse response = contactService.logActivity(1L, request(ActivityType.CALL));

        assertThat(response.status()).isEqualTo(OutreachStatus.ENGAGED);
        assertThat(response.touchCount()).isZero();
        assertThat(response.nextActionDate()).isNull();
        verify(activityLogger).forContact(eq(contact), eq(ActivityType.CALL), eq(true), isNull(), eq(user));
    }

    @Test
    void anExplicitNextActionOverridesTheDefault() {
        contact(OutreachStatus.NEW, 0);

        ContactResponse response = contactService.logActivity(1L, new LogActivityRequest(
                ActivityType.LINKEDIN, null, LocalDate.of(2026, 10, 20), ActionType.CALL, false, null));

        assertThat(response.nextActionDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(response.nextActionType()).isEqualTo(ActionType.CALL);
    }

    @Test
    void restartingTheSequenceResetsTouchesAndSchedulesToday() {
        contact(OutreachStatus.NO_RESPONSE, 4);

        ContactResponse response = contactService.restartSequence(1L);

        assertThat(response.status()).isEqualTo(OutreachStatus.NEW);
        assertThat(response.touchCount()).isZero();
        assertThat(response.nextActionDate()).isEqualTo(TODAY);
        assertThat(response.nextActionType()).isEqualTo(ActionType.LINKEDIN);
    }

    @Test
    void theSequenceAlternatesLinkedinAndEmail() {
        assertThat(ContactService.channelForTouch(0)).isEqualTo(ActionType.LINKEDIN);
        assertThat(ContactService.channelForTouch(1)).isEqualTo(ActionType.EMAIL);
        assertThat(ContactService.channelForTouch(2)).isEqualTo(ActionType.LINKEDIN);
        assertThat(ContactService.channelForTouch(3)).isEqualTo(ActionType.EMAIL);
        assertThat(ContactService.channelForTouch(9)).isEqualTo(ActionType.EMAIL);
    }

    @Test
    void theDailyQueueTakesOneContactPerCompanyInTurn() {
        Company acme = company(10L, "Acme AI");
        Company zest = company(11L, "Zest Risk");
        Company lumen = company(12L, "Lumen");
        List<Contact> oldestFirst = new ArrayList<>(List.of(
                person(1L, acme), person(2L, acme), person(3L, acme),
                person(4L, zest), person(5L, lumen)));

        List<Long> picked = ContactService.rotateByCompany(oldestFirst, 4).stream()
                .map(Contact::getId)
                .toList();

        assertThat(picked).containsExactly(1L, 4L, 5L, 2L);
    }

    @Test
    void systemActivityTypesCannotBeLoggedByHand() {
        contact(OutreachStatus.NEW, 0);

        assertThatThrownBy(() -> contactService.logActivity(1L, request(ActivityType.STATUS_CHANGE)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void creatingAContactDefaultsToNewAndTheSignedInOwner() {
        Company company = company();
        when(companyRepository.findById(10L)).thenReturn(Optional.of(company));
        when(contactRepository.save(any(Contact.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContactResponse response = contactService.create(new ContactRequest(
                "Priya Shah", "Head of ML Platform", "priya@acme.ai", null, null, null,
                BuyingRole.BUYER, null, null, null, null, 10L, null));

        assertThat(response.status()).isEqualTo(OutreachStatus.NEW);
        assertThat(response.ownerId()).isEqualTo(7L);
        assertThat(response.companyName()).isEqualTo("Acme AI");
        assertThat(response.buyingRole()).isEqualTo(BuyingRole.BUYER);
    }

    @Test
    void duplicateEmailsAreRejected() {
        when(companyRepository.findById(10L)).thenReturn(Optional.of(company()));
        // Emails are stored encrypted, so duplicates are found by their keyed hash.
        when(contactRepository.existsByEmailHash(encryptor.blindIndex("Priya@Acme.ai"))).thenReturn(true);

        assertThatThrownBy(() -> contactService.create(new ContactRequest(
                "Priya Shah", null, "priya@acme.ai", null, null, null,
                null, null, null, null, null, 10L, null)))
                .isInstanceOf(ConflictException.class);
    }

    private Contact contact(OutreachStatus status, int touches) {
        Contact contact = new Contact();
        contact.setId(1L);
        contact.setName("Jane Doe");
        contact.setCompany(company());
        contact.setStatus(status);
        contact.setTouchCount(touches);
        when(contactRepository.findWithDetailsById(1L)).thenReturn(Optional.of(contact));
        return contact;
    }

    private static Company company() {
        return company(10L, "Acme AI");
    }

    private static Company company(Long id, String name) {
        Company company = new Company();
        company.setId(id);
        company.setName(name);
        return company;
    }

    private static Contact person(Long id, Company company) {
        Contact contact = new Contact();
        contact.setId(id);
        contact.setName("Person " + id);
        contact.setCompany(company);
        return contact;
    }

    private static LogActivityRequest request(ActivityType type) {
        return new LogActivityRequest(type, null, null, null, false, null);
    }
}
