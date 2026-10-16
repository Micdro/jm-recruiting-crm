package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.activity.Activity;
import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.activity.LogActivityRequest;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.BusinessDays;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.Later;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.RecentChanges;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.trash.TrashKind;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contacts and the outbound rules the firm runs on: one row per person, track only the
 * next action, and at most four touches in a cold sequence, all on LinkedIn.
 */
@Service
@Transactional(readOnly = true)
public class ContactService {

    public static final int MAX_SEQUENCE_TOUCHES = 4;
    public static final int TOUCH_GAP_BUSINESS_DAYS = 3;
    public static final int MAX_FILL_QUEUE = 100;
    /**
     * Days after a sequence ends with no reply before the contact comes back to Today, ready to
     * restart: long enough not to pester, short enough that budgets and teams will have moved on.
     */
    public static final int REVISIT_DAYS = 90;

    /** The channel for each touch in the cold sequence. The firm reaches out on LinkedIn only. */
    public static final List<ActionType> SEQUENCE_CHANNELS =
            Collections.nCopies(MAX_SEQUENCE_TOUCHES, ActionType.LINKEDIN);

    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final FieldEncryptor encryptor;
    private final Clock clock;
    private final TrashService trash;
    private final RecentChanges recentChanges;

    public ContactService(
            ContactRepository contactRepository,
            CompanyRepository companyRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            FieldEncryptor encryptor,
            Clock clock,
            TrashService trash,
            RecentChanges recentChanges
    ) {
        this.trash = trash;
        this.recentChanges = recentChanges;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    /** The channel for the touch after {@code touchesSent}. */
    public static ActionType channelForTouch(int touchesSent) {
        int index = Math.max(0, Math.min(touchesSent, SEQUENCE_CHANNELS.size() - 1));
        return SEQUENCE_CHANNELS.get(index);
    }

    /** When a contact whose sequence ended on this day comes back to Today: 90 days on, never a weekend. */
    public static LocalDate revisitDate(LocalDate sequenceEnded) {
        LocalDate date = sequenceEnded.plusDays(REVISIT_DAYS);
        while (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    public List<ContactResponse> list(Long companyId, OutreachStatus status, Long ownerId, boolean dueOnly, String q) {
        LocalDate today = LocalDate.now(clock);
        String query = Text.key(q);

        return contactRepository.findAllWithDetails()
                .stream()
                .filter(contact -> companyId == null || contact.getCompany().getId().equals(companyId))
                .filter(contact -> status == null || contact.getStatus() == status)
                .filter(contact -> ownerId == null
                        || (contact.getOwner() != null && contact.getOwner().getId().equals(ownerId)))
                .filter(contact -> !dueOnly || isDue(contact, today))
                .filter(contact -> query == null || matches(contact, query))
                .sorted(Comparator.comparing((Contact contact) -> contact.getName().toLowerCase()))
                .map(ContactResponse::summary)
                .toList();
    }

    public ContactResponse get(Long id) {
        return ContactResponse.from(findContact(id));
    }

    @Transactional
    public ContactResponse create(ContactRequest request) {
        AppUser user = currentUserService.get();
        Company company = findCompany(request.companyId());

        String email = Text.clean(request.email());
        if (email != null && contactRepository.existsByEmailHash(encryptor.blindIndex(email))) {
            throw new ConflictException("A contact with this email already exists");
        }

        Contact contact = new Contact();
        apply(request, contact, company);
        contact.setStatus(request.status() == null ? OutreachStatus.NEW : request.status());
        contact.setOwner(request.ownerId() == null ? user : currentUserService.findOwner(request.ownerId()));

        return ContactResponse.from(contactRepository.save(contact));
    }

    @Transactional
    public ContactResponse update(Long id, ContactRequest request) {
        Contact contact = findContact(id);
        Company company = findCompany(request.companyId());

        String email = Text.clean(request.email());
        if (email != null && contactRepository.existsByEmailHashAndIdNot(encryptor.blindIndex(email), id)) {
            throw new ConflictException("A contact with this email already exists");
        }

        OutreachStatus previousStatus = contact.getStatus();

        apply(request, contact, company);
        if (request.status() != null) {
            contact.setStatus(request.status());
        }
        contact.setOwner(currentUserService.findOwner(request.ownerId()));

        if (previousStatus != contact.getStatus()) {
            activityLogger.forContact(contact, ActivityType.STATUS_CHANGE, false,
                    "Status changed from " + Labels.of(previousStatus) + " to " + Labels.of(contact.getStatus()),
                    currentUserService.get());
        }

        return ContactResponse.from(contact);
    }

    @Transactional
    public void delete(Long id) {
        trash.capture(TrashKind.CONTACT, id);
        if (contactRepository.deleteContactById(id) == 0) {
            throw new NotFoundException("Contact not found");
        }
    }

    /**
     * Logs a touch, reply, meeting or note and moves the contact along:
     * a cold touch counts toward the four-touch limit and schedules the next touch three
     * business days out; the fourth touch ends the sequence; a reply puts a response on today's list.
     */
    @Transactional
    public ContactResponse logActivity(Long id, LogActivityRequest request) {
        Contact contact = findContact(id);
        AppUser user = currentUserService.get();
        ActivityType type = request.type();
        LocalDate today = LocalDate.now(clock);
        OutreachState before = OutreachState.of(contact);

        if (!type.isManual()) {
            throw new BadRequestException("That activity type is recorded automatically");
        }

        boolean outreach = type.isOutreach();
        boolean sequenceTouch = false;
        String prefix = null;

        if (outreach) {
            if (contact.getStatus() == OutreachStatus.DO_NOT_CONTACT) {
                throw new ConflictException("This contact is marked Do not contact");
            }
            if (contact.getStatus().needsRestart()) {
                throw new ConflictException("This contact's sequence is over (" + Labels.of(contact.getStatus())
                        + "). Restart the sequence to reach out again.");
            }
            if (contact.getStatus().isCold()) {
                if (contact.getTouchCount() >= MAX_SEQUENCE_TOUCHES) {
                    throw new ConflictException(
                            "This contact already has 4 touches. Restart the sequence to reach out again.");
                }
                contact.setTouchCount(contact.getTouchCount() + 1);
                contact.setStatus(OutreachStatus.IN_SEQUENCE);
                sequenceTouch = true;
                prefix = "Touch " + contact.getTouchCount() + " of " + MAX_SEQUENCE_TOUCHES;
            }
            contact.setLastContactedDate(today);
        } else if (type == ActivityType.MEETING) {
            contact.setLastContactedDate(today);
        } else if (type == ActivityType.REPLY && waitingForReply(contact.getStatus())) {
            contact.setStatus(OutreachStatus.REPLIED);
        }

        boolean sequenceFinished = sequenceTouch && contact.getTouchCount() >= MAX_SEQUENCE_TOUCHES;
        if (sequenceFinished) {
            contact.setStatus(OutreachStatus.NO_RESPONSE);
        }

        applyNextAction(contact, request, today, sequenceTouch, sequenceFinished);

        Activity activity = activityLogger.forContact(
                contact, type, outreach, joinSummary(prefix, request.notes()), user);
        rememberChange(contact, user, activity, before);

        return ContactResponse.from(contact);
    }

    /**
     * Moves the next action to the next business day or a week out, without opening the contact.
     * Recorded in the history, and can be undone like a touch.
     */
    @Transactional
    public ContactResponse snooze(Long id, Later until) {
        Contact contact = findContact(id);
        if (contact.getNextActionDate() == null) {
            throw new ConflictException(contact.getName() + " has no next action to move.");
        }

        AppUser user = currentUserService.get();
        OutreachState before = OutreachState.of(contact);
        LocalDate date = until.from(LocalDate.now(clock));
        contact.setNextActionDate(date);

        Activity activity = activityLogger.forContact(contact, ActivityType.STATUS_CHANGE, false,
                "Next action moved to " + Later.day(date), user);
        rememberChange(contact, user, activity, before);

        return ContactResponse.from(contact);
    }

    /**
     * Undoes the signed-in person's last touch, reply, note or snooze on this contact, made in the
     * last few minutes: the touch count, status and next action go back to what they were, and
     * the history entry goes, so the day's tally and the weekly numbers drop it too.
     */
    @Transactional
    public ContactResponse undo(Long id) {
        Contact contact = findContact(id);
        AppUser user = currentUserService.get();
        RecentChanges.Change<OutreachState> change =
                recentChanges.take(RecentChanges.Kind.CONTACT, id, user.getId(), OutreachState.class);

        if (!OutreachState.of(contact).equals(change.after())) {
            throw new ConflictException(contact.getName() + " changed after that, so it can't be undone.");
        }

        change.before().applyTo(contact);
        activityLogger.remove(change.activityId());

        return ContactResponse.from(contact);
    }

    private void rememberChange(Contact contact, AppUser user, Activity activity, OutreachState before) {
        recentChanges.remember(RecentChanges.Kind.CONTACT, contact.getId(), user.getId(), activity.getId(),
                before, OutreachState.of(contact));
    }

    /** The parts of a contact that logging a touch or moving the next action can change. */
    record OutreachState(
            OutreachStatus status,
            int touchCount,
            LocalDate lastContactedDate,
            LocalDate nextActionDate,
            ActionType nextActionType
    ) {
        static OutreachState of(Contact contact) {
            return new OutreachState(contact.getStatus(), contact.getTouchCount(), contact.getLastContactedDate(),
                    contact.getNextActionDate(), contact.getNextActionType());
        }

        void applyTo(Contact contact) {
            contact.setStatus(status);
            contact.setTouchCount(touchCount);
            contact.setLastContactedDate(lastContactedDate);
            contact.setNextActionDate(nextActionDate);
            contact.setNextActionType(nextActionType);
        }
    }

    /** Starts a fresh four-touch sequence, due today. */
    @Transactional
    public ContactResponse restartSequence(Long id) {
        Contact contact = findContact(id);

        if (contact.getStatus() == OutreachStatus.DO_NOT_CONTACT) {
            throw new ConflictException("This contact is marked Do not contact");
        }

        AppUser user = currentUserService.get();
        OutreachState before = OutreachState.of(contact);
        OutreachStatus previousStatus = contact.getStatus();
        contact.setTouchCount(0);
        contact.setStatus(OutreachStatus.NEW);
        contact.setNextActionDate(LocalDate.now(clock));
        contact.setNextActionType(channelForTouch(0));

        Activity activity = activityLogger.forContact(contact, ActivityType.STATUS_CHANGE, false,
                "Sequence restarted (was " + Labels.of(previousStatus) + ")", user);
        // Restarting from Today's queue is one click, so it can be undone like a touch.
        rememberChange(contact, user, activity, before);

        return ContactResponse.from(contact);
    }

    /**
     * Pulls new, unscheduled contacts into today's queue so the day's touch target can be met.
     * Takes the signed-in user's contacts and unassigned ones, oldest first, rotating through
     * companies so one day's outreach doesn't land on the same company several times.
     */
    @Transactional
    public FillQueueResponse fillQueue(int count) {
        if (count < 1 || count > MAX_FILL_QUEUE) {
            throw new BadRequestException("Choose between 1 and " + MAX_FILL_QUEUE + " contacts");
        }

        AppUser user = currentUserService.get();
        LocalDate today = LocalDate.now(clock);

        List<Contact> contacts = rotateByCompany(
                contactRepository.findUnscheduled(OutreachStatus.NEW, user.getId()), count);

        for (Contact contact : contacts) {
            contact.setNextActionDate(today);
            contact.setNextActionType(channelForTouch(contact.getTouchCount()));
            if (contact.getOwner() == null) {
                contact.setOwner(user);
            }
        }

        long remaining = contactRepository.countUnscheduled(OutreachStatus.NEW, user.getId());
        return new FillQueueResponse(contacts.size(), remaining);
    }

    /** Takes one contact per company in turn, keeping each company's oldest first. */
    static List<Contact> rotateByCompany(List<Contact> oldestFirst, int count) {
        Map<Long, Deque<Contact>> byCompany = new LinkedHashMap<>();
        for (Contact contact : oldestFirst) {
            byCompany.computeIfAbsent(contact.getCompany().getId(), key -> new ArrayDeque<>()).add(contact);
        }

        List<Contact> picked = new ArrayList<>();
        while (picked.size() < count && !byCompany.isEmpty()) {
            var companies = byCompany.values().iterator();
            while (companies.hasNext() && picked.size() < count) {
                Deque<Contact> queue = companies.next();
                picked.add(queue.poll());
                if (queue.isEmpty()) {
                    companies.remove();
                }
            }
        }
        return picked;
    }

    public long countUnscheduledForCurrentUser() {
        return contactRepository.countUnscheduled(OutreachStatus.NEW, currentUserService.get().getId());
    }

    public static boolean isDue(Contact contact, LocalDate today) {
        return contact.getNextActionDate() != null
                && !contact.getNextActionDate().isAfter(today)
                && !contact.getStatus().isClosed();
    }

    private static boolean waitingForReply(OutreachStatus status) {
        return status.isCold() || status == OutreachStatus.NO_RESPONSE || status == OutreachStatus.NURTURE;
    }

    private static void applyNextAction(
            Contact contact,
            LogActivityRequest request,
            LocalDate today,
            boolean sequenceTouch,
            boolean sequenceFinished
    ) {
        ActivityType type = request.type();

        if (request.clearsNextAction()) {
            clearNextAction(contact);
            return;
        }

        if (request.nextActionDate() != null) {
            contact.setNextActionDate(request.nextActionDate());
            contact.setNextActionType(request.nextActionType() != null
                    ? request.nextActionType()
                    : type.toActionType());
            return;
        }

        if (sequenceTouch) {
            if (sequenceFinished) {
                // No reply after four touches: back on Today in 90 days, ready to restart.
                contact.setNextActionDate(revisitDate(today));
                contact.setNextActionType(ActionType.LINKEDIN);
            } else {
                contact.setNextActionDate(BusinessDays.add(today, TOUCH_GAP_BUSINESS_DAYS));
                contact.setNextActionType(request.nextActionType() != null
                        ? request.nextActionType()
                        : channelForTouch(contact.getTouchCount()));
            }
            return;
        }

        if (type == ActivityType.REPLY) {
            contact.setNextActionDate(today);
            contact.setNextActionType(ActionType.LINKEDIN);
            return;
        }

        // Any other outreach or meeting completes whatever was due.
        boolean completesAction = type.isOutreach() || type == ActivityType.MEETING;
        if (completesAction && contact.getNextActionDate() != null && !contact.getNextActionDate().isAfter(today)) {
            clearNextAction(contact);
        }
    }

    private static void clearNextAction(Contact contact) {
        contact.setNextActionDate(null);
        contact.setNextActionType(null);
    }

    private static String joinSummary(String prefix, String notes) {
        String cleanNotes = Text.clean(notes);
        if (prefix == null) {
            return cleanNotes;
        }
        return cleanNotes == null ? prefix : prefix + ": " + cleanNotes;
    }

    private static boolean matches(Contact contact, String query) {
        return contains(contact.getName(), query)
                || contains(contact.getEmail(), query)
                || contains(contact.getNotes(), query)
                || contains(contact.getTitle(), query)
                || contains(contact.getCompany().getName(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private void apply(ContactRequest request, Contact contact, Company company) {
        contact.setName(request.name().trim());
        contact.setTitle(Text.clean(request.title()));
        contact.setEmail(Text.clean(request.email()));
        contact.setPhone(Text.clean(request.phone()));
        contact.setLinkedinUrl(Text.clean(request.linkedinUrl()));
        contact.setLastContactedDate(request.lastContactedDate());
        contact.setBuyingRole(request.buyingRole());
        contact.setNextActionDate(request.nextActionDate());
        contact.setNextActionType(request.nextActionDate() == null
                ? null
                : request.nextActionType() != null ? request.nextActionType() : channelForTouch(contact.getTouchCount()));
        contact.setNotes(Text.clean(request.notes()));
        contact.setCompany(company);
    }

    private Contact findContact(Long id) {
        return contactRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Contact not found"));
    }

    private Company findCompany(Long id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Company not found"));
    }

    public record FillQueueResponse(int scheduled, long remaining) {
    }
}
