package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.BulkResult;
import com.janemichael.jmrecruitingcrm.common.BulkResult.Skipped;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.RecentChanges;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.trash.TrashKind;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import com.janemichael.jmrecruitingcrm.undo.UndoLog;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bulk actions on the Contacts list: change the owner or status of many contacts at once, put
 * them on today's queue, or delete them. Each action is one transaction and one Undo.
 */
@Service
@Transactional
public class ContactBulkService {

    /**
     * RESTART puts contacts back to New with no touches and nothing scheduled, so Pull in on Today
     * adds them a few companies at a time rather than all at once.
     */
    public enum Action { OWNER, STATUS, TODAY, RESTART, DELETE }

    /** ownerId is the new owner for OWNER (null for unassigned); status is the new status for STATUS. */
    public record Request(
            @NotEmpty(message = "Choose at least one contact")
            @Size(max = BulkResult.MAX_IDS, message = "Choose at most " + BulkResult.MAX_IDS + " contacts at a time")
            List<Long> ids,
            @NotNull(message = "Choose what to do") Action action,
            Long ownerId,
            OutreachStatus status
    ) {
    }

    /** The fields a bulk action can change, kept so Undo can put them back. */
    record State(OutreachStatus status, int touchCount, Long ownerId, LocalDate nextActionDate,
                 ActionType nextActionType) {
        static State of(Contact contact) {
            return new State(contact.getStatus(), contact.getTouchCount(),
                    contact.getOwner() == null ? null : contact.getOwner().getId(),
                    contact.getNextActionDate(), contact.getNextActionType());
        }
    }

    record Item(long id, State before, State after, Long activityId) {
    }

    /** One bulk action: the changed contacts, or the deleted ones. */
    record Batch(List<Item> items, List<Long> deletedIds) {
    }

    private final ContactRepository contactRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final TrashService trash;
    private final RecentChanges recentChanges;
    private final Clock clock;
    private final UndoLog undoLog;

    public ContactBulkService(
            ContactRepository contactRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            TrashService trash,
            RecentChanges recentChanges,
            Clock clock,
            UndoLog undoLog
    ) {
        this.undoLog = undoLog;
        this.contactRepository = contactRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
        this.trash = trash;
        this.recentChanges = recentChanges;
        this.clock = clock;
    }

    public BulkResult apply(Request request) {
        AppUser user = currentUserService.get();
        List<Long> ids = request.ids().stream().filter(Objects::nonNull).distinct().toList();

        if (request.action() == Action.DELETE) {
            return delete(ids, user);
        }
        if (request.action() == Action.STATUS && request.status() == null) {
            throw new BadRequestException("Choose a status");
        }
        AppUser owner = request.action() == Action.OWNER ? currentUserService.findOwner(request.ownerId()) : null;
        LocalDate today = LocalDate.now(clock);

        Map<Long, Contact> found = contactRepository.findWithDetailsByIds(ids).stream()
                .collect(Collectors.toMap(Contact::getId, Function.identity()));
        List<Item> items = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (Long id : ids) {
            Contact contact = found.get(id);
            if (contact == null) {
                skipped.add(new Skipped(id, null, "Not found"));
                continue;
            }
            String reason = switch (request.action()) {
                case OWNER -> sameOwner(contact.getOwner(), owner) ? alreadyOwned(owner) : null;
                case STATUS -> contact.getStatus() == request.status() ? "Already " + Labels.of(request.status()) : null;
                case TODAY -> whyNotToday(contact, today);
                case RESTART -> whyNotRestart(contact);
                case DELETE -> throw new IllegalStateException();
            };
            if (reason != null) {
                skipped.add(new Skipped(id, contact.getName(), reason));
                continue;
            }

            State before = State.of(contact);
            Long activityId = null;
            switch (request.action()) {
                case OWNER -> contact.setOwner(owner);
                case STATUS -> {
                    contact.setStatus(request.status());
                    activityId = activityLogger.forContact(contact, ActivityType.STATUS_CHANGE, false,
                            "Status changed from " + Labels.of(before.status()) + " to " + Labels.of(request.status()),
                            user).getId();
                }
                case TODAY -> {
                    contact.setNextActionDate(today);
                    contact.setNextActionType(contact.getStatus().isCold()
                            ? ContactService.channelForTouch(contact.getTouchCount())
                            : Objects.requireNonNullElse(contact.getNextActionType(), ActionType.LINKEDIN));
                    // Today's queue shows each person their own contacts, as Pull in does.
                    if (contact.getOwner() == null) {
                        contact.setOwner(user);
                    }
                }
                case RESTART -> {
                    contact.setTouchCount(0);
                    contact.setStatus(OutreachStatus.NEW);
                    contact.setNextActionDate(null);
                    contact.setNextActionType(null);
                    activityId = activityLogger.forContact(contact, ActivityType.STATUS_CHANGE, false,
                            "Sequence restarted (was " + Labels.of(before.status()) + ")", user).getId();
                }
                case DELETE -> throw new IllegalStateException();
            }
            items.add(new Item(id, before, State.of(contact), activityId));
        }

        Long undoId = items.isEmpty() ? null
                : recentChanges.rememberBatch(RecentChanges.Kind.CONTACT_BATCH, user.getId(), new Batch(items, List.of()));
        if (!items.isEmpty()) {
            String contacts = UndoLog.count(items.size(), "contact", "contacts");
            undoLog.record(switch (request.action()) {
                case OWNER -> owner == null ? "Unassigned " + contacts : "Gave " + contacts + " to " + owner.getDisplayName();
                case STATUS -> "Marked " + contacts + " " + Labels.of(request.status());
                case TODAY -> "Added " + contacts + " to today's queue";
                case RESTART -> "Restarted the sequence for " + contacts;
                case DELETE -> throw new IllegalStateException();
            }, UndoLog.Kind.OTHER, null);
        }
        return new BulkResult(items.size(), skipped, undoId);
    }

    /**
     * Undoes a bulk action from the last few minutes. Contacts someone has changed since are left
     * as they are, and listed.
     */
    public BulkResult undo(long undoId) {
        AppUser user = currentUserService.get();
        Batch batch = recentChanges.takeBatch(RecentChanges.Kind.CONTACT_BATCH, undoId, user.getId(), Batch.class);
        List<Skipped> skipped = new ArrayList<>();
        int undone = 0;

        if (!batch.deletedIds().isEmpty()) {
            for (Long id : batch.deletedIds()) {
                try {
                    trash.restoreLatest(TrashKind.CONTACT, id);
                    undone++;
                } catch (NotFoundException gone) {
                    skipped.add(new Skipped(id, null, "No longer in the Trash"));
                }
            }
            return new BulkResult(undone, skipped, null);
        }

        Map<Long, Contact> found = contactRepository.findWithDetailsByIds(
                        batch.items().stream().map(Item::id).toList()).stream()
                .collect(Collectors.toMap(Contact::getId, Function.identity()));
        Map<Long, AppUser> owners = new HashMap<>();

        for (Item item : batch.items()) {
            Contact contact = found.get(item.id());
            if (contact == null) {
                skipped.add(new Skipped(item.id(), null, "Deleted since"));
                continue;
            }
            if (!State.of(contact).equals(item.after())) {
                skipped.add(new Skipped(item.id(), contact.getName(), "Changed since"));
                continue;
            }
            State before = item.before();
            contact.setStatus(before.status());
            contact.setTouchCount(before.touchCount());
            contact.setOwner(before.ownerId() == null ? null
                    : owners.computeIfAbsent(before.ownerId(), currentUserService::findOwner));
            contact.setNextActionDate(before.nextActionDate());
            contact.setNextActionType(before.nextActionType());
            activityLogger.remove(item.activityId());
            undone++;
        }
        return new BulkResult(undone, skipped, null);
    }

    private BulkResult delete(List<Long> ids, AppUser user) {
        List<Long> deleted = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Long id : ids) {
            // Each goes to the Trash first, with its history, as a single delete does.
            trash.capture(TrashKind.CONTACT, id);
            if (contactRepository.deleteContactById(id) == 0) {
                skipped.add(new Skipped(id, null, "Not found"));
            } else {
                deleted.add(id);
            }
        }
        Long undoId = deleted.isEmpty() ? null
                : recentChanges.rememberBatch(RecentChanges.Kind.CONTACT_BATCH, user.getId(), new Batch(List.of(), deleted));
        if (!deleted.isEmpty()) {
            undoLog.record("Deleted " + UndoLog.count(deleted.size(), "contact", "contacts"), UndoLog.Kind.OTHER, null);
        }
        return new BulkResult(deleted.size(), skipped, undoId);
    }

    /** Why a contact can't go on today's queue, or null if it can. */
    static String whyNotToday(Contact contact, LocalDate today) {
        OutreachStatus status = contact.getStatus();
        if (status == OutreachStatus.DO_NOT_CONTACT) return "Marked Do not contact";
        if (status == OutreachStatus.NOT_INTERESTED) return "Not interested";
        if (status == OutreachStatus.NO_RESPONSE
                || (status.isCold() && contact.getTouchCount() >= ContactService.MAX_SEQUENCE_TOUCHES)) {
            return "Sequence over; restart it first";
        }
        if (contact.getCompany().getStatus() == CompanyStatus.AI_FOUND) return "Company not reviewed yet";
        if (contact.getNextActionDate() != null && !contact.getNextActionDate().isAfter(today)) return "Already due";
        return null;
    }

    /** Why a contact's sequence can't be restarted, or null if it can. */
    static String whyNotRestart(Contact contact) {
        if (contact.getStatus() == OutreachStatus.DO_NOT_CONTACT) return "Marked Do not contact";
        if (contact.getStatus() == OutreachStatus.NEW && contact.getTouchCount() == 0) return "Not started yet";
        return null;
    }

    private static boolean sameOwner(AppUser current, AppUser wanted) {
        return current == null ? wanted == null : wanted != null && current.getId().equals(wanted.getId());
    }

    private static String alreadyOwned(AppUser owner) {
        return owner == null ? "Already unassigned" : "Already " + owner.getDisplayName() + "'s";
    }
}
