package com.janemichael.jmrecruitingcrm.dashboard;

import com.janemichael.jmrecruitingcrm.activity.ActivityRepository;
import com.janemichael.jmrecruitingcrm.activity.ActivityResponse;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.candidate.CandidateResponse;
import com.janemichael.jmrecruitingcrm.candidate.CandidateService;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyResponse;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.ContactResponse;
import com.janemichael.jmrecruitingcrm.contact.ContactService;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.placement.Placement;
import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.PlacementResponse;
import com.janemichael.jmrecruitingcrm.placement.PlacementRepository;
import com.janemichael.jmrecruitingcrm.search.SearchResponse;
import com.janemichael.jmrecruitingcrm.search.SearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the Today page needs in one call. */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    static final int MAX_DUE_ITEMS = 200;
    static final int RECENT_ACTIVITY = 15;
    static final int GUARANTEE_WINDOW_DAYS = 30;

    private final CurrentUserService currentUserService;
    private final AppUserRepository userRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final CandidateRepository candidateRepository;
    private final PlacementRepository placementRepository;
    private final ActivityRepository activityRepository;
    private final SearchService searchService;
    private final Clock clock;
    private final int dailyTouchTarget;
    private final int dailyTouchStretch;

    public DashboardService(
            CurrentUserService currentUserService,
            AppUserRepository userRepository,
            ContactRepository contactRepository,
            CompanyRepository companyRepository,
            CandidateRepository candidateRepository,
            PlacementRepository placementRepository,
            ActivityRepository activityRepository,
            SearchService searchService,
            Clock clock,
            @Value("${app.outreach.daily-touch-target:30}") int dailyTouchTarget,
            @Value("${app.outreach.daily-touch-stretch:50}") int dailyTouchStretch
    ) {
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.candidateRepository = candidateRepository;
        this.placementRepository = placementRepository;
        this.activityRepository = activityRepository;
        this.searchService = searchService;
        this.clock = clock;
        this.dailyTouchTarget = dailyTouchTarget;
        this.dailyTouchStretch = dailyTouchStretch;
    }

    public DashboardResponse get(boolean team) {
        AppUser me = currentUserService.get();
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);

        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone)
                .toInstant();

        Map<Long, Long> touchesToday = toCountMap(activityRepository.countTouchesByUser(dayStart, dayEnd));
        Map<Long, Long> touchesThisWeek = toCountMap(activityRepository.countTouchesByUser(weekStart, dayEnd));

        List<TeamMemberTouches> teamTouches = userRepository.findAllByOrderByDisplayNameAsc()
                .stream()
                .filter(AppUser::isActive)
                .map(user -> new TeamMemberTouches(
                        user.getId(),
                        user.getDisplayName(),
                        touchesToday.getOrDefault(user.getId(), 0L),
                        touchesThisWeek.getOrDefault(user.getId(), 0L)))
                .toList();

        List<Contact> dueContacts = contactRepository.findAllWithDetails()
                .stream()
                .filter(contact -> ContactService.isDue(contact, today))
                .filter(contact -> team || ownedBy(contact.getOwner(), me))
                .sorted(Comparator.comparing(Contact::getNextActionDate)
                        .thenComparing(contact -> contact.getName().toLowerCase()))
                .toList();

        List<Candidate> dueCandidates = candidateRepository.findAllWithOwner()
                .stream()
                .filter(candidate -> CandidateService.isDue(candidate, today))
                .filter(candidate -> team || ownedBy(candidate.getOwner(), me))
                .sorted(Comparator.comparing(Candidate::getNextActionDate)
                        .thenComparing(candidate -> candidate.getName().toLowerCase()))
                .toList();

        List<Company> dueCompanies = companyRepository.findAllWithOwner()
                .stream()
                .filter(company -> company.getFollowUpDate() != null && !company.getFollowUpDate().isAfter(today))
                .filter(company -> team || ownedBy(company.getOwner(), me))
                .sorted(Comparator.comparing(Company::getFollowUpDate)
                        .thenComparing(company -> company.getName().toLowerCase()))
                .toList();

        List<SearchResponse> openSearches = searchService.list(null, null, true);

        Map<String, Long> outreachByStatus = new LinkedHashMap<>();
        for (OutreachStatus status : OutreachStatus.values()) {
            outreachByStatus.put(status.name(), 0L);
        }
        for (Object[] row : contactRepository.countByStatus()) {
            outreachByStatus.put(((OutreachStatus) row[0]).name(), (Long) row[1]);
        }

        List<ActivityResponse> recentActivity = activityRepository
                .findRecent(PageRequest.of(0, RECENT_ACTIVITY))
                .stream()
                .map(ActivityResponse::from)
                .toList();

        return new DashboardResponse(
                today,
                dailyTouchTarget,
                dailyTouchStretch,
                touchesToday.getOrDefault(me.getId(), 0L),
                touchesThisWeek.getOrDefault(me.getId(), 0L),
                teamTouches,
                dueContacts.stream().limit(MAX_DUE_ITEMS).map(ContactResponse::summary).toList(),
                dueContacts.size(),
                dueCandidates.stream().limit(MAX_DUE_ITEMS).map(candidate -> CandidateResponse.summary(candidate, 0L)).toList(),
                dueCandidates.size(),
                dueCompanies.stream().limit(MAX_DUE_ITEMS).map(company -> CompanyResponse.summary(company, 0L, 0L, null)).toList(),
                dueCompanies.size(),
                contactRepository.countUnscheduled(OutreachStatus.NEW, me.getId()),
                openSearches,
                outreachByStatus,
                placementSummary(today),
                recentActivity
        );
    }

    private PlacementSummary placementSummary(LocalDate today) {
        int year = today.getYear();
        List<Placement> placements = placementRepository.findAllWithDetails();

        long placementsThisYear = 0;
        BigDecimal feesThisYear = BigDecimal.ZERO;
        BigDecimal collectedThisYear = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;

        for (Placement placement : placements) {
            BigDecimal fee = placement.getFeeAmount() == null ? BigDecimal.ZERO : placement.getFeeAmount();
            LocalDate counted = placement.getStartDate() != null
                    ? placement.getStartDate()
                    : LocalDate.ofInstant(placement.getCreatedAt(), clock.getZone());

            if (counted.getYear() == year) {
                placementsThisYear++;
                feesThisYear = feesThisYear.add(fee);
            }
            if (placement.getInvoiceStatus() == Placement.InvoiceStatus.PAID
                    && placement.getPaidDate() != null
                    && placement.getPaidDate().getYear() == year) {
                collectedThisYear = collectedThisYear.add(fee);
            }
            if (placement.getInvoiceStatus() == Placement.InvoiceStatus.INVOICED) {
                outstanding = outstanding.add(fee);
            }
        }

        LocalDate windowEnd = today.plusDays(GUARANTEE_WINDOW_DAYS);
        List<PlacementResponse> guaranteesEndingSoon = placements.stream()
                .filter(placement -> placement.getGuaranteeEndDate() != null)
                .filter(placement -> !placement.getGuaranteeEndDate().isBefore(today)
                        && !placement.getGuaranteeEndDate().isAfter(windowEnd))
                .sorted(Comparator.comparing(Placement::getGuaranteeEndDate))
                .map(PlacementResponse::from)
                .toList();

        List<PlacementResponse> overdueInvoices = placements.stream()
                .filter(placement -> placement.getInvoiceStatus() == Placement.InvoiceStatus.INVOICED)
                .filter(placement -> placement.effectiveDueDate() != null && placement.effectiveDueDate().isBefore(today))
                .sorted(Comparator.comparing(Placement::effectiveDueDate))
                .map(PlacementResponse::from)
                .toList();

        return new PlacementSummary(placementsThisYear, feesThisYear, collectedThisYear, outstanding,
                guaranteesEndingSoon, overdueInvoices);
    }

    private static boolean ownedBy(AppUser owner, AppUser me) {
        return owner != null && owner.getId().equals(me.getId());
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                counts.put((Long) row[0], (Long) row[1]);
            }
        }
        return counts;
    }

    public record TeamMemberTouches(Long userId, String name, long today, long thisWeek) {
    }

    public record PlacementSummary(
            long placementsThisYear,
            BigDecimal feesThisYear,
            BigDecimal collectedThisYear,
            BigDecimal outstanding,
            List<PlacementResponse> guaranteesEndingSoon,
            /** Sent and unpaid past their due date, oldest first. */
            List<PlacementResponse> overdueInvoices
    ) {
    }

    public record DashboardResponse(
            LocalDate today,
            int dailyTouchTarget,
            int dailyTouchStretch,
            long myTouchesToday,
            long myTouchesThisWeek,
            List<TeamMemberTouches> teamTouches,
            List<ContactResponse> contactsDue,
            long contactsDueTotal,
            List<CandidateResponse> candidatesDue,
            long candidatesDueTotal,
            List<CompanyResponse> companiesDue,
            long companiesDueTotal,
            long unscheduledNewContacts,
            List<SearchResponse> openSearches,
            Map<String, Long> outreachByStatus,
            PlacementSummary placements,
            List<ActivityResponse> recentActivity
    ) {
    }
}
