package com.janemichael.jmrecruitingcrm.company;

import com.janemichael.jmrecruitingcrm.activity.Activity;
import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.EnumCsv;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.Later;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.RecentChanges;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.placement.PlacementRepository;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
import com.janemichael.jmrecruitingcrm.trash.TrashKind;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import com.janemichael.jmrecruitingcrm.undo.UndoLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class CompanyService {

    public static final String HAS_CONTACTS_MESSAGE =
            "Company has contacts. Delete or reassign them before deleting the company.";
    public static final String HAS_SEARCHES_MESSAGE =
            "Company has searches. Delete them before deleting the company.";

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final SearchRepository searchRepository;
    private final PlacementRepository placementRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final FieldEncryptor encryptor;
    private final TrashService trash;
    private final Clock clock;
    private final RecentChanges recentChanges;
    private final UndoLog undoLog;

    public CompanyService(
            CompanyRepository companyRepository,
            ContactRepository contactRepository,
            SearchRepository searchRepository,
            PlacementRepository placementRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            FieldEncryptor encryptor,
            TrashService trash,
            Clock clock,
            RecentChanges recentChanges,
            UndoLog undoLog
    ) {
        this.undoLog = undoLog;
        this.trash = trash;
        this.clock = clock;
        this.recentChanges = recentChanges;
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.searchRepository = searchRepository;
        this.placementRepository = placementRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
        this.encryptor = encryptor;
    }

    public List<CompanyResponse> list(String q, CompanyStatus status) {
        String query = Text.key(q);
        Map<Long, Long> contactCounts = contactCounts();
        Map<Long, Long> openSearchCounts = openSearchCounts();
        Map<Long, LocalDate> lastPlacementStarts = lastPlacementStarts();
        LocalDate today = LocalDate.now(clock);

        return companyRepository.findAllWithOwner()
                .stream()
                .filter(company -> status == null || company.getStatus() == status)
                .filter(company -> query == null || matches(company, query))
                // Names are encrypted in the database, so they're sorted here.
                .sorted(Comparator.comparing((Company company) -> company.getName().toLowerCase()))
                .map(company -> {
                    long openSearches = openSearchCounts.getOrDefault(company.getId(), 0L);
                    return CompanyResponse.summary(company,
                            contactCounts.getOrDefault(company.getId(), 0L),
                            openSearches,
                            OffLimits.of(company, openSearches, lastPlacementStarts.get(company.getId()), today));
                })
                .toList();
    }

    public CompanyResponse get(Long id) {
        return response(find(id));
    }

    @Transactional
    public CompanyResponse create(CompanyRequest request) {
        AppUser user = currentUserService.get();
        String name = request.name().trim();

        if (companyRepository.existsByNameHash(encryptor.blindIndex(name))) {
            throw new ConflictException("A company with this name already exists");
        }

        Company company = new Company();
        apply(request, company);
        company.setStatus(request.status() == null ? CompanyStatus.LEAD : request.status());
        company.setOwner(request.ownerId() == null ? user : currentUserService.findOwner(request.ownerId()));

        Company saved = companyRepository.save(company);
        undoLog.record("Added " + saved.getName(), UndoLog.Kind.COMPANY, saved.getId());
        return response(saved);
    }

    @Transactional
    public CompanyResponse update(Long id, CompanyRequest request) {
        Company company = find(id);
        String name = request.name().trim();

        if (companyRepository.existsByNameHashAndIdNot(encryptor.blindIndex(name), id)) {
            throw new ConflictException("A company with this name already exists");
        }

        CompanyStatus previousStatus = company.getStatus();

        apply(request, company);
        if (request.status() != null) {
            company.setStatus(request.status());
        }
        company.setOwner(currentUserService.findOwner(request.ownerId()));

        if (previousStatus != company.getStatus()) {
            activityLogger.forCompany(company, ActivityType.STATUS_CHANGE,
                    "Status changed from " + Labels.of(previousStatus) + " to " + Labels.of(company.getStatus()),
                    currentUserService.get());
        }

        undoLog.record("Edited " + company.getName(), UndoLog.Kind.COMPANY, id);
        return response(company);
    }

    @Transactional
    public void delete(Long id) {
        if (contactRepository.existsByCompanyId(id)) {
            throw new ConflictException(HAS_CONTACTS_MESSAGE);
        }
        if (searchRepository.existsByCompanyId(id)) {
            throw new ConflictException(HAS_SEARCHES_MESSAGE);
        }
        companyRepository.findById(id).ifPresent(company ->
                undoLog.record("Deleted " + company.getName(), UndoLog.Kind.COMPANY, id));
        trash.capture(TrashKind.COMPANY, id);
        if (companyRepository.deleteCompanyById(id) == 0) {
            throw new NotFoundException("Company not found");
        }
    }

    /** Moves the follow-up date to the next business day or a week out, from the Today queue. */
    @Transactional
    public CompanyResponse snooze(Long id, Later until) {
        Company company = find(id);
        if (company.getFollowUpDate() == null) {
            throw new ConflictException(company.getName() + " has no follow-up date to move.");
        }

        AppUser user = currentUserService.get();
        FollowUp before = new FollowUp(company.getFollowUpDate());
        LocalDate date = until.from(LocalDate.now(clock));
        company.setFollowUpDate(date);

        Activity activity = activityLogger.forCompany(company, ActivityType.STATUS_CHANGE,
                "Follow-up moved to " + Later.day(date), user);
        recentChanges.remember(RecentChanges.Kind.COMPANY, id, user.getId(), activity.getId(),
                before, new FollowUp(date));

        undoLog.record("Moved " + company.getName() + "'s follow-up to " + Later.day(date),
                UndoLog.Kind.COMPANY, id);
        return response(company);
    }

    /** Undoes your last snooze of this company's follow-up from the last few minutes. */
    @Transactional
    public CompanyResponse undo(Long id) {
        Company company = find(id);
        RecentChanges.Change<FollowUp> change = recentChanges.take(
                RecentChanges.Kind.COMPANY, id, currentUserService.get().getId(), FollowUp.class);

        if (!new FollowUp(company.getFollowUpDate()).equals(change.after())) {
            throw new ConflictException(company.getName() + " changed after that, so it can't be undone.");
        }

        company.setFollowUpDate(change.before().date());
        activityLogger.remove(change.activityId());

        return response(company);
    }

    /** The part of a company that moving its follow-up changes. */
    record FollowUp(LocalDate date) {
    }

    private CompanyResponse response(Company company) {
        Long id = company.getId();
        long openSearches = openSearchCounts().getOrDefault(id, 0L);
        return CompanyResponse.from(company,
                contactCounts().getOrDefault(id, 0L),
                openSearches,
                OffLimits.of(company, openSearches, lastPlacementStarts().get(id), LocalDate.now(clock)));
    }

    /** The day the most recent placement at each company starts. */
    private Map<Long, LocalDate> lastPlacementStarts() {
        Map<Long, LocalDate> starts = new HashMap<>();
        for (Object[] row : placementRepository.findLastStartByCompany()) {
            starts.put((Long) row[0], (LocalDate) row[1]);
        }
        return starts;
    }

    private Map<Long, Long> contactCounts() {
        return toCountMap(contactRepository.countByCompany());
    }

    private Map<Long, Long> openSearchCounts() {
        List<SearchEnums.Status> openStatuses = Arrays.stream(SearchEnums.Status.values())
                .filter(SearchEnums.Status::isOpen)
                .toList();
        return toCountMap(searchRepository.countByCompanyAndStatusIn(openStatuses));
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static boolean matches(Company company, String query) {
        return contains(company.getName(), query)
                || contains(company.getFocus(), query)
                || contains(company.getNotes(), query)
                || contains(company.getLocation(), query)
                || contains(company.getWebsite(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private static void apply(CompanyRequest request, Company company) {
        company.setName(request.name().trim());
        company.setWebsite(Text.clean(request.website()));
        company.setLocation(Text.clean(request.location()));
        company.setCompanySize(Text.clean(request.companySize()));
        company.setFocus(Text.clean(request.focus()));
        company.setIndustry(request.industry());
        company.setSegments(EnumCsv.join(request.segments()));
        company.setTier(request.tier());
        company.setPriority(request.priority());
        company.setHiringNeed(request.hiringNeed());
        company.setHiringTimeline(request.hiringTimeline());
        company.setAnticipatedHires(request.anticipatedHires());
        company.setLeadSource(request.leadSource());
        company.setLastContactedDate(request.lastContactedDate());
        company.setFollowUpDate(request.followUpDate());
        company.setOffLimitsUntil(request.offLimitsUntil());
        company.setLinkedinProfile(Text.clean(request.linkedinProfile()));
        company.setNotes(Text.clean(request.notes()));
    }

    private Company find(Long id) {
        return companyRepository.findWithOwnerById(id)
                .orElseThrow(() -> new NotFoundException("Company not found"));
    }
}
