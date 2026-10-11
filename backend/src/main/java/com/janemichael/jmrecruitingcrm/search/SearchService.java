package com.janemichael.jmrecruitingcrm.search;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineRepository;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineStage;
import com.janemichael.jmrecruitingcrm.placement.PlacementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private final SearchRepository searchRepository;
    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final PipelineRepository pipelineRepository;
    private final PlacementRepository placementRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final Clock clock;

    public SearchService(
            SearchRepository searchRepository,
            CompanyRepository companyRepository,
            ContactRepository contactRepository,
            PipelineRepository pipelineRepository,
            PlacementRepository placementRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            Clock clock
    ) {
        this.searchRepository = searchRepository;
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.pipelineRepository = pipelineRepository;
        this.placementRepository = placementRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
        this.clock = clock;
    }

    public List<SearchResponse> list(Long companyId, SearchEnums.Status status, boolean openOnly) {
        Map<Long, Map<String, Long>> stageCounts = stageCounts();

        return searchRepository.findAllWithDetails()
                .stream()
                .filter(search -> companyId == null || search.getCompany().getId().equals(companyId))
                .filter(search -> status == null || search.getStatus() == status)
                .filter(search -> !openOnly || search.getStatus().isOpen())
                .map(search -> SearchResponse.from(search, stageCounts.get(search.getId())))
                .toList();
    }

    public SearchResponse get(Long id) {
        return SearchResponse.from(find(id), stageCounts().get(id));
    }

    /** The fee when none is entered: 20% of first-year base, as in the ClickUp revenue formula. */
    public static final BigDecimal DEFAULT_FEE_PERCENT = new BigDecimal("20");

    /** Opens a search. The company moves to Placing, since a search means the firm is working for them. */
    @Transactional
    public SearchResponse create(SearchRequest request) {
        AppUser user = currentUserService.get();
        Company company = findCompany(request.companyId());
        validate(request);

        Search search = new Search();
        apply(request, search, company);
        search.setStatus(request.status() == null ? SearchEnums.Status.INTAKE : request.status());
        search.setEngagementType(request.engagementType() == null
                ? SearchEnums.EngagementType.CONTINGENCY
                : request.engagementType());
        search.setOpenedDate(request.openedDate() == null ? LocalDate.now(clock) : request.openedDate());
        search.setOwner(request.ownerId() == null ? user : currentUserService.findOwner(request.ownerId()));
        if (search.getFeePercent() == null) {
            search.setFeePercent(DEFAULT_FEE_PERCENT);
        }

        Search saved = searchRepository.save(search);
        activityLogger.forSearch(saved, ActivityType.NOTE, "Search opened: " + saved.getTitle(), user);

        if (company.getStatus() != CompanyStatus.PLACING) {
            CompanyStatus previous = company.getStatus();
            company.setStatus(CompanyStatus.PLACING);
            activityLogger.forCompany(company, ActivityType.STATUS_CHANGE,
                    "Status changed from " + Labels.of(previous) + " to Placing (search opened)", user);
        }

        return SearchResponse.from(saved, Map.of());
    }

    @Transactional
    public SearchResponse update(Long id, SearchRequest request) {
        Search search = find(id);
        Company company = findCompany(request.companyId());
        validate(request);

        SearchEnums.Status previousStatus = search.getStatus();

        apply(request, search, company);
        if (request.status() != null) {
            search.setStatus(request.status());
        }
        if (request.engagementType() != null) {
            search.setEngagementType(request.engagementType());
        }
        if (request.openedDate() != null) {
            search.setOpenedDate(request.openedDate());
        }
        search.setOwner(currentUserService.findOwner(request.ownerId()));

        if (previousStatus != search.getStatus()) {
            activityLogger.forSearch(search, ActivityType.STATUS_CHANGE,
                    "Status changed from " + Labels.of(previousStatus) + " to " + Labels.of(search.getStatus()),
                    currentUserService.get());
        }

        return SearchResponse.from(search, stageCounts().get(id));
    }

    @Transactional
    public void delete(Long id) {
        if (placementRepository.existsBySearchId(id)) {
            throw new ConflictException("This search has a placement. Delete the placement first.");
        }
        if (searchRepository.deleteSearchById(id) == 0) {
            throw new NotFoundException("Search not found");
        }
    }

    private Map<Long, Map<String, Long>> stageCounts() {
        Map<Long, Map<String, Long>> counts = new HashMap<>();
        for (Object[] row : pipelineRepository.countBySearchAndStage()) {
            Long searchId = (Long) row[0];
            PipelineStage stage = (PipelineStage) row[1];
            Long count = (Long) row[2];
            counts.computeIfAbsent(searchId, key -> new HashMap<>()).put(stage.name(), count);
        }
        return counts;
    }

    private static void validate(SearchRequest request) {
        if (request.baseMin() != null && request.baseMax() != null && request.baseMax() < request.baseMin()) {
            throw new BadRequestException("Base salary maximum must be at least the minimum");
        }
    }

    private void apply(SearchRequest request, Search search, Company company) {
        search.setCompany(company);
        search.setHiringManager(findHiringManager(request.hiringManagerId(), company));
        search.setTitle(request.title().trim());
        search.setTrack(request.track());
        search.setFeePercent(request.feePercent());
        search.setBaseMin(request.baseMin());
        search.setBaseMax(request.baseMax());
        search.setLocation(Text.clean(request.location()));
        search.setWorkModel(request.workModel());
        search.setTimeline(request.timeline());
        search.setProductionOutcome(Text.clean(request.productionOutcome()));
        search.setTechnicalScope(Text.clean(request.technicalScope()));
        search.setAccountability(Text.clean(request.accountability()));
        search.setRegulatoryConstraints(Text.clean(request.regulatoryConstraints()));
        search.setNotes(Text.clean(request.notes()));
    }

    private Contact findHiringManager(Long contactId, Company company) {
        if (contactId == null) {
            return null;
        }
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new BadRequestException("Hiring manager not found"));
        if (!contact.getCompany().getId().equals(company.getId())) {
            throw new BadRequestException("The hiring manager must work at the search's company");
        }
        return contact;
    }

    private Search find(Long id) {
        return searchRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Search not found"));
    }

    private Company findCompany(Long id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Company not found"));
    }
}
