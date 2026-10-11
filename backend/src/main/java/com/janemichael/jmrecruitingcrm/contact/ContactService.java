package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.activity.LogActivityRequest;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.BusinessDays;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Contacts and the outbound rules the firm runs on: one row per person, track only the
 * next action, and at most four touches in a cold sequence.
 */
@Service
@Transactional(readOnly = true)
public class ContactService {

    public static final int MAX_SEQUENCE_TOUCHES = 4;
    public static final int TOUCH_GAP_BUSINESS_DAYS = 3;
    public static final int MAX_FILL_QUEUE = 100;

    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final Clock clock;

    public ContactService(
            ContactRepository contactRepository,
            CompanyRepository companyRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            Clock clock
    ) {
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
        this.clock = clock;
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
                .map(ContactResponse::from)
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
        if (email != null && contactRepository.existsByEmailIgnoreCase(email)) {
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
        if (email != null && contactRepository.existsByEmailIgnoreCaseAndIdNot(email, id)) {
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

        activityLogger.forContact(contact, type, outreach, joinSummary(prefix, request.notes()), user);

        return ContactResponse.from(contact);
    }

    /** Starts a fresh four-touch sequence, due today. */
    @Transactional
    public ContactResponse restartSequence(Long id) {
        Contact contact = findContact(id);

        if (contact.getStatus() == OutreachStatus.DO_NOT_CONTACT) {
            throw new ConflictException("This contact is marked Do not contact");
        }

        OutreachStatus previousStatus = contact.getStatus();
        contact.setTouchCount(0);
        contact.setStatus(OutreachStatus.NEW);
        contact.setNextActionDate(LocalDate.now(clock));
        if (contact.getNextActionType() == null) {
            contact.setNextActionType(ActionType.EMAIL);
        }

        activityLogger.forContact(contact, ActivityType.STATUS_CHANGE, false,
                "Sequence restarted (was " + Labels.of(previousStatus) + ")", currentUserService.get());

        return ContactResponse.from(contact);
    }

    /**
     * Pulls new, unscheduled contacts into today's queue so the day's touch target can be met.
     * Takes the signed-in user's contacts and unassigned ones, oldest first.
     */
    @Transactional
    public FillQueueResponse fillQueue(int count) {
        if (count < 1 || count > MAX_FILL_QUEUE) {
            throw new BadRequestException("Choose between 1 and " + MAX_FILL_QUEUE + " contacts");
        }

        AppUser user = currentUserService.get();
        LocalDate today = LocalDate.now(clock);

        List<Contact> contacts = contactRepository.findUnscheduled(
                OutreachStatus.NEW, user.getId(), PageRequest.of(0, count));

        for (Contact contact : contacts) {
            contact.setNextActionDate(today);
            if (contact.getNextActionType() == null) {
                contact.setNextActionType(ActionType.EMAIL);
            }
            if (contact.getOwner() == null) {
                contact.setOwner(user);
            }
        }

        long remaining = contactRepository.countUnscheduled(OutreachStatus.NEW, user.getId());
        return new FillQueueResponse(contacts.size(), remaining);
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
                clearNextAction(contact);
            } else {
                contact.setNextActionDate(BusinessDays.add(today, TOUCH_GAP_BUSINESS_DAYS));
                contact.setNextActionType(request.nextActionType() != null
                        ? request.nextActionType()
                        : type.toActionType());
            }
            return;
        }

        if (type == ActivityType.REPLY) {
            contact.setNextActionDate(today);
            contact.setNextActionType(ActionType.EMAIL);
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
        contact.setNextActionDate(request.nextActionDate());
        contact.setNextActionType(request.nextActionDate() == null ? null : request.nextActionType());
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
