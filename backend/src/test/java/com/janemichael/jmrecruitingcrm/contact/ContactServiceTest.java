package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.activity.Activity;
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
import com.janemichael.jmrecruitingcrm.common.Later;
import com.janemichael.jmrecruitingcrm.common.RecentChanges;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import com.janemichael.jmrecruitingcrm.undo.UndoLog;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
            mock(TrashService.class), new RecentChanges(clock), mock(UndoLog.class));

    private final AppUser user = new AppUser();

    @BeforeEach
    void setUp() {
        user.setId(7L);
        user.setEmail("michael@jane-michael.com");
        user.setDisplayName("Michael");
        user.setRole(UserRole.ADMIN);
        when(currentUserService.get()).thenReturn(user);
        // Each history entry gets the next id, as the database would give it.
        long[] nextId = {100};
        when(activityLogger.forContact(any(), any(), anyBoolean(), any(), any())).thenAnswer(invocation -> {
            Activity activity = new Activity();
            activity.setId(nextId[0]++);
            return activity;
        });
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
        assertThat(response.nextActionType()).isEqualTo(ActionType.LINKEDIN);
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
        // Back on Today in 90 days to restart: Wednesday, October 14 plus 90 days is Tuesday, January 12.
        assertThat(response.nextActionDate()).isEqualTo(LocalDate.of(2027, 1, 12));
        assertThat(response.nextActionType()).isEqualTo(ActionType.LINKEDIN);
    }

    @Test
    void aRevisitNeverFallsOnAWeekend() {
        // Sunday, October 18 plus 90 days is a Saturday, so the revisit moves to Monday.
        assertThat(ContactService.revisitDate(LocalDate.of(2026, 10, 18))).isEqualTo(LocalDate.of(2027, 1, 18));
        assertThat(ContactService.revisitDate(TODAY)).isEqualTo(LocalDate.of(2027, 1, 12));
    }

    @Test
    void restartingFromTodayCanBeUndone() {
        Contact contact = contact(OutreachStatus.NO_RESPONSE, 4);
        contact.setNextActionDate(TODAY);
        contact.setNextActionType(ActionType.LINKEDIN);

        contactService.restartSequence(1L);
        assertThat(contact.getStatus()).isEqualTo(OutreachStatus.NEW);

        ContactResponse undone = contactService.undo(1L);
        assertThat(undone.status()).isEqualTo(OutreachStatus.NO_RESPONSE);
        assertThat(undone.touchCount()).isEqualTo(4);
        assertThat(undone.nextActionDate()).isEqualTo(TODAY);
        verify(activityLogger).remove(100L);
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
        assertThat(response.nextActionType()).isEqualTo(ActionType.LINKEDIN);
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
    void everyTouchOfTheSequenceIsOnLinkedin() {
        for (int touchesSent : new int[] {0, 1, 2, 3, 9}) {
            assertThat(ContactService.channelForTouch(touchesSent)).isEqualTo(ActionType.LINKEDIN);
        }
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

    @Test
    void undoingATouchPutsTheContactBackAndRemovesTheHistoryEntry() {
        Contact contact = contact(OutreachStatus.IN_SEQUENCE, 1);
        contact.setNextActionDate(TODAY);
        contact.setNextActionType(ActionType.LINKEDIN);
        contact.setLastContactedDate(LocalDate.of(2026, 10, 9));

        contactService.logActivity(1L, request(ActivityType.LINKEDIN));
        assertThat(contact.getTouchCount()).isEqualTo(2);

        ContactResponse undone = contactService.undo(1L);

        assertThat(undone.status()).isEqualTo(OutreachStatus.IN_SEQUENCE);
        assertThat(undone.touchCount()).isEqualTo(1);
        assertThat(undone.nextActionDate()).isEqualTo(TODAY);
        assertThat(undone.nextActionType()).isEqualTo(ActionType.LINKEDIN);
        assertThat(undone.lastContactedDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        verify(activityLogger).remove(100L);
    }

    @Test
    void undoingTheFourthTouchReopensTheSequence() {
        Contact contact = contact(OutreachStatus.IN_SEQUENCE, 3);
        contact.setNextActionDate(TODAY);
        contact.setNextActionType(ActionType.LINKEDIN);

        contactService.logActivity(1L, request(ActivityType.LINKEDIN));
        assertThat(contact.getStatus()).isEqualTo(OutreachStatus.NO_RESPONSE);

        ContactResponse undone = contactService.undo(1L);

        assertThat(undone.status()).isEqualTo(OutreachStatus.IN_SEQUENCE);
        assertThat(undone.touchCount()).isEqualTo(3);
        assertThat(undone.nextActionDate()).isEqualTo(TODAY);
    }

    @Test
    void aChangeCanOnlyBeUndoneOnce() {
        contact(OutreachStatus.IN_SEQUENCE, 1).setNextActionDate(TODAY);
        contactService.logActivity(1L, request(ActivityType.LINKEDIN));
        contactService.undo(1L);

        assertThatThrownBy(() -> contactService.undo(1L))
                .isInstanceOf(ConflictException.class)
                .hasMessage(RecentChanges.NOTHING_TO_UNDO);
    }

    @Test
    void undoRefusesWhenTheContactChangedSince() {
        Contact contact = contact(OutreachStatus.IN_SEQUENCE, 1);
        contact.setNextActionDate(TODAY);
        contactService.logActivity(1L, request(ActivityType.LINKEDIN));

        // A teammate (or a sync) moved the contact on in the meantime.
        contact.setStatus(OutreachStatus.REPLIED);

        assertThatThrownBy(() -> contactService.undo(1L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("changed after that");
        assertThat(contact.getStatus()).isEqualTo(OutreachStatus.REPLIED);
        verify(activityLogger, never()).remove(any());
    }

    @Test
    void onlyThePersonWhoMadeTheChangeCanUndoIt() {
        contact(OutreachStatus.IN_SEQUENCE, 1).setNextActionDate(TODAY);
        contactService.logActivity(1L, request(ActivityType.LINKEDIN));

        AppUser teammate = new AppUser();
        teammate.setId(8L);
        when(currentUserService.get()).thenReturn(teammate);

        assertThatThrownBy(() -> contactService.undo(1L))
                .isInstanceOf(ConflictException.class)
                .hasMessage(RecentChanges.NOTHING_TO_UNDO);
    }

    @Test
    void laterMovesTheNextActionToTheNextBusinessDayAndRecordsIt() {
        Contact contact = contact(OutreachStatus.IN_SEQUENCE, 2);
        contact.setNextActionDate(LocalDate.of(2026, 10, 12));
        contact.setNextActionType(ActionType.LINKEDIN);

        ContactResponse response = contactService.snooze(1L, Later.TOMORROW);

        assertThat(response.nextActionDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(response.nextActionType()).isEqualTo(ActionType.LINKEDIN);
        assertThat(response.touchCount()).isEqualTo(2);
        verify(activityLogger).forContact(contact, ActivityType.STATUS_CHANGE, false,
                "Next action moved to Thu, Oct 15", user);
    }

    @Test
    void aSnoozeCanBeUndone() {
        Contact contact = contact(OutreachStatus.IN_SEQUENCE, 2);
        contact.setNextActionDate(LocalDate.of(2026, 10, 12));

        contactService.snooze(1L, Later.NEXT_WEEK);
        assertThat(contact.getNextActionDate()).isEqualTo(LocalDate.of(2026, 10, 21));

        contactService.undo(1L);

        assertThat(contact.getNextActionDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        verify(activityLogger).remove(100L);
    }

    @Test
    void aContactWithNothingScheduledCannotBeSnoozed() {
        contact(OutreachStatus.ENGAGED, 0);

        assertThatThrownBy(() -> contactService.snooze(1L, Later.TOMORROW))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no next action");
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
