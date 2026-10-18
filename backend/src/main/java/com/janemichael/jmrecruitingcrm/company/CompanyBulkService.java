package com.janemichael.jmrecruitingcrm.company;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.BulkResult;
import com.janemichael.jmrecruitingcrm.common.BulkResult.Skipped;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.RecentChanges;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
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
 * Bulk actions on the Companies list: change the owner, stage or priority of many companies at
 * once, put their follow-up on today, or delete them. Each action is one transaction and one Undo.
 */
@Service
@Transactional
public class CompanyBulkService {

    public enum Action { OWNER, STAGE, PRIORITY, FOLLOW_UP_TODAY, DELETE }

    /** ownerId is the new owner for OWNER (null for unassigned); status and priority go with STAGE and PRIORITY. */
    public record Request(
            @NotEmpty(message = "Choose at least one company")
            @Size(max = BulkResult.MAX_IDS, message = "Choose at most " + BulkResult.MAX_IDS + " companies at a time")
            List<Long> ids,
            @NotNull(message = "Choose what to do") Action action,
            Long ownerId,
            CompanyStatus status,
            CompanyEnums.Priority priority
    ) {
    }

    /** The fields a bulk action can change, kept so Undo can put them back. */
    record State(CompanyStatus status, Long ownerId, CompanyEnums.Priority priority, LocalDate followUpDate) {
        static State of(Company company) {
            return new State(company.getStatus(), company.getOwner() == null ? null : company.getOwner().getId(),
                    company.getPriority(), company.getFollowUpDate());
        }
    }

    record Item(long id, State before, State after, Long activityId) {
    }

    /** One bulk action: the changed companies, or the deleted ones. */
    record Batch(List<Item> items, List<Long> deletedIds) {
    }

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final SearchRepository searchRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final TrashService trash;
    private final RecentChanges recentChanges;
    private final Clock clock;
    private final UndoLog undoLog;

    public CompanyBulkService(
            CompanyRepository companyRepository,
            ContactRepository contactRepository,
            SearchRepository searchRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            TrashService trash,
            RecentChanges recentChanges,
            Clock clock,
            UndoLog undoLog
    ) {
        this.undoLog = undoLog;
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.searchRepository = searchRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
        this.trash = trash;
        this.recentChanges = recentChanges;
        this.clock = clock;
    }

    public BulkResult apply(Request request) {
        AppUser user = currentUserService.get();
        List<Long> ids = request.ids().stream().filter(Objects::nonNull).distinct().toList();
        Action action = request.action();

        if (action == Action.STAGE && request.status() == null) {
            throw new BadRequestException("Choose a stage");
        }
        if (action == Action.PRIORITY && request.priority() == null) {
            throw new BadRequestException("Choose a priority");
        }
        AppUser owner = action == Action.OWNER ? currentUserService.findOwner(request.ownerId()) : null;
        LocalDate today = LocalDate.now(clock);

        Map<Long, Company> found = companyRepository.findWithOwnerByIds(ids).stream()
                .collect(Collectors.toMap(Company::getId, Function.identity()));
        if (action == Action.DELETE) {
            return delete(ids, found, user);
        }

        List<Item> items = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (Long id : ids) {
            Company company = found.get(id);
            if (company == null) {
                skipped.add(new Skipped(id, null, "Not found"));
                continue;
            }
            String reason = switch (action) {
                case OWNER -> sameOwner(company.getOwner(), owner) ? alreadyOwned(owner) : null;
                case STAGE -> company.getStatus() == request.status() ? "Already " + Labels.of(request.status()) : null;
                case PRIORITY -> company.getPriority() == request.priority()
                        ? "Already " + Labels.of(request.priority()) + " priority" : null;
                case FOLLOW_UP_TODAY -> company.getFollowUpDate() != null && !company.getFollowUpDate().isAfter(today)
                        ? "Already due" : null;
                case DELETE -> throw new IllegalStateException();
            };
            if (reason != null) {
                skipped.add(new Skipped(id, company.getName(), reason));
                continue;
            }

            State before = State.of(company);
            Long activityId = null;
            switch (action) {
                case OWNER -> company.setOwner(owner);
                case STAGE -> {
                    company.setStatus(request.status());
                    activityId = activityLogger.forCompany(company, ActivityType.STATUS_CHANGE,
                            "Status changed from " + Labels.of(before.status()) + " to " + Labels.of(request.status()),
                            user).getId();
                }
                case PRIORITY -> company.setPriority(request.priority());
                case FOLLOW_UP_TODAY -> {
                    company.setFollowUpDate(today);
                    // Today shows each person their own follow-ups.
                    if (company.getOwner() == null) {
                        company.setOwner(user);
                    }
                }
                case DELETE -> throw new IllegalStateException();
            }
            items.add(new Item(id, before, State.of(company), activityId));
        }

        Long undoId = items.isEmpty() ? null
                : recentChanges.rememberBatch(RecentChanges.Kind.COMPANY_BATCH, user.getId(), new Batch(items, List.of()));
        if (!items.isEmpty()) {
            String companies = UndoLog.count(items.size(), "company", "companies");
            undoLog.record(switch (action) {
                case OWNER -> owner == null ? "Unassigned " + companies : "Gave " + companies + " to " + owner.getDisplayName();
                case STAGE -> "Moved " + companies + " to " + Labels.of(request.status());
                case PRIORITY -> "Set " + companies + " to " + Labels.of(request.priority()) + " priority";
                case FOLLOW_UP_TODAY -> "Set " + companies + " to follow up today";
                case DELETE -> throw new IllegalStateException();
            }, UndoLog.Kind.OTHER, null);
        }
        return new BulkResult(items.size(), skipped, undoId);
    }

    /**
     * Undoes a bulk action from the last few minutes. Companies someone has changed since are left
     * as they are, and listed.
     */
    public BulkResult undo(long undoId) {
        AppUser user = currentUserService.get();
        Batch batch = recentChanges.takeBatch(RecentChanges.Kind.COMPANY_BATCH, undoId, user.getId(), Batch.class);
        List<Skipped> skipped = new ArrayList<>();
        int undone = 0;

        if (!batch.deletedIds().isEmpty()) {
            for (Long id : batch.deletedIds()) {
                try {
                    trash.restoreLatest(TrashKind.COMPANY, id);
                    undone++;
                } catch (NotFoundException gone) {
                    skipped.add(new Skipped(id, null, "No longer in the Trash"));
                }
            }
            return new BulkResult(undone, skipped, null);
        }

        Map<Long, Company> found = companyRepository.findWithOwnerByIds(
                        batch.items().stream().map(Item::id).toList()).stream()
                .collect(Collectors.toMap(Company::getId, Function.identity()));
        Map<Long, AppUser> owners = new HashMap<>();

        for (Item item : batch.items()) {
            Company company = found.get(item.id());
            if (company == null) {
                skipped.add(new Skipped(item.id(), null, "Deleted since"));
                continue;
            }
            if (!State.of(company).equals(item.after())) {
                skipped.add(new Skipped(item.id(), company.getName(), "Changed since"));
                continue;
            }
            State before = item.before();
            company.setStatus(before.status());
            company.setOwner(before.ownerId() == null ? null
                    : owners.computeIfAbsent(before.ownerId(), currentUserService::findOwner));
            company.setPriority(before.priority());
            company.setFollowUpDate(before.followUpDate());
            activityLogger.remove(item.activityId());
            undone++;
        }
        return new BulkResult(undone, skipped, null);
    }

    /** Companies with contacts or searches stay, as when deleting one company. */
    private BulkResult delete(List<Long> ids, Map<Long, Company> found, AppUser user) {
        List<Long> deleted = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Long id : ids) {
            Company company = found.get(id);
            if (company == null) {
                skipped.add(new Skipped(id, null, "Not found"));
            } else if (contactRepository.existsByCompanyId(id)) {
                skipped.add(new Skipped(id, company.getName(), "Has contacts; delete or move them first"));
            } else if (searchRepository.existsByCompanyId(id)) {
                skipped.add(new Skipped(id, company.getName(), "Has searches; delete them first"));
            } else {
                trash.capture(TrashKind.COMPANY, id);
                companyRepository.deleteCompanyById(id);
                deleted.add(id);
            }
        }
        Long undoId = deleted.isEmpty() ? null
                : recentChanges.rememberBatch(RecentChanges.Kind.COMPANY_BATCH, user.getId(), new Batch(List.of(), deleted));
        if (!deleted.isEmpty()) {
            undoLog.record("Deleted " + UndoLog.count(deleted.size(), "company", "companies"), UndoLog.Kind.OTHER, null);
        }
        return new BulkResult(deleted.size(), skipped, undoId);
    }

    private static boolean sameOwner(AppUser current, AppUser wanted) {
        return current == null ? wanted == null : wanted != null && current.getId().equals(wanted.getId());
    }

    private static String alreadyOwned(AppUser owner) {
        return owner == null ? "Already unassigned" : "Already " + owner.getDisplayName() + "'s";
    }
}
