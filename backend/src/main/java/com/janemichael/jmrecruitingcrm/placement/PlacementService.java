package com.janemichael.jmrecruitingcrm.placement;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.candidate.CandidateEnums;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineEntry;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineRepository;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineService;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineStage;
import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.PlacementRequest;
import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.PlacementResponse;
import com.janemichael.jmrecruitingcrm.search.Search;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
import com.janemichael.jmrecruitingcrm.trash.TrashKind;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class PlacementService {

    /** Default replacement guarantee when none is entered. */
    public static final int DEFAULT_GUARANTEE_DAYS = 90;

    private final PlacementRepository placementRepository;
    private final SearchRepository searchRepository;
    private final CandidateRepository candidateRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineService pipelineService;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final TrashService trash;

    public PlacementService(
            PlacementRepository placementRepository,
            SearchRepository searchRepository,
            CandidateRepository candidateRepository,
            PipelineRepository pipelineRepository,
            PipelineService pipelineService,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            TrashService trash
    ) {
        this.trash = trash;
        this.placementRepository = placementRepository;
        this.searchRepository = searchRepository;
        this.candidateRepository = candidateRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineService = pipelineService;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
    }

    public List<PlacementResponse> list() {
        return placementRepository.findAllWithDetails()
                .stream()
                .map(PlacementResponse::from)
                .toList();
    }

    public PlacementResponse get(Long id) {
        return PlacementResponse.from(find(id));
    }

    /** Records a hire. The candidate's pipeline entry moves to Placed and they are marked placed. */
    @Transactional
    public PlacementResponse create(PlacementRequest request) {
        AppUser user = currentUserService.get();
        Search search = findSearch(request.searchId());
        Candidate candidate = findCandidate(request.candidateId());

        if (placementRepository.existsBySearchIdAndCandidateId(search.getId(), candidate.getId())) {
            throw new ConflictException("This candidate already has a placement for this search");
        }

        Placement placement = new Placement();
        placement.setSearch(search);
        placement.setCandidate(candidate);
        apply(request, placement, search);
        Placement saved = placementRepository.save(placement);

        Optional<PipelineEntry> entry = pipelineRepository.findBySearchAndCandidate(search.getId(), candidate.getId());
        if (entry.isPresent()) {
            pipelineService.changeStage(entry.get(), PipelineStage.PLACED, user);
        } else {
            pipelineService.createEntry(search, candidate, PipelineStage.PLACED, null, user);
        }
        candidate.setStatus(CandidateEnums.Status.PLACED);

        String summary = "Placement recorded for " + search.getTitle()
                + (saved.getStartDate() == null ? "" : ", starting " + saved.getStartDate());
        activityLogger.forCandidate(candidate, search, ActivityType.NOTE, false, summary, user);

        return PlacementResponse.from(saved);
    }

    @Transactional
    public PlacementResponse update(Long id, PlacementRequest request) {
        Placement placement = find(id);

        if (!placement.getSearch().getId().equals(request.searchId())
                || !placement.getCandidate().getId().equals(request.candidateId())) {
            if (placementRepository.existsBySearchIdAndCandidateIdAndIdNot(
                    request.searchId(), request.candidateId(), id)) {
                throw new ConflictException("This candidate already has a placement for this search");
            }
            placement.setSearch(findSearch(request.searchId()));
            placement.setCandidate(findCandidate(request.candidateId()));
        }

        apply(request, placement, placement.getSearch());
        return PlacementResponse.from(placement);
    }

    @Transactional
    public void delete(Long id) {
        trash.capture(TrashKind.PLACEMENT, id);
        if (placementRepository.deletePlacementById(id) == 0) {
            throw new NotFoundException("Placement not found");
        }
    }

    /** Base salary times the fee percent, rounded to cents. Null when either is missing. */
    public static BigDecimal calculateFee(Integer baseSalary, BigDecimal feePercent) {
        if (baseSalary == null || feePercent == null) {
            return null;
        }
        return BigDecimal.valueOf(baseSalary)
                .multiply(feePercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static void apply(PlacementRequest request, Placement placement, Search search) {
        BigDecimal feePercent = request.feePercent() != null ? request.feePercent() : search.getFeePercent();
        BigDecimal feeAmount = request.feeAmount() != null
                ? request.feeAmount()
                : calculateFee(request.baseSalary(), feePercent);

        LocalDate guaranteeEnd = request.guaranteeEndDate();
        if (guaranteeEnd == null && request.startDate() != null) {
            guaranteeEnd = request.startDate().plusDays(DEFAULT_GUARANTEE_DAYS);
        }

        placement.setStartDate(request.startDate());
        placement.setBaseSalary(request.baseSalary());
        placement.setFeePercent(feePercent);
        placement.setFeeAmount(feeAmount);
        placement.setInvoiceStatus(request.invoiceStatus() == null
                ? Placement.InvoiceStatus.NOT_INVOICED
                : request.invoiceStatus());
        placement.setInvoiceDate(request.invoiceDate());
        placement.setPaidDate(request.paidDate());
        placement.setGuaranteeEndDate(guaranteeEnd);
        placement.setNotes(Text.clean(request.notes()));
    }

    private Placement find(Long id) {
        return placementRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Placement not found"));
    }

    private Search findSearch(Long id) {
        return searchRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Search not found"));
    }

    private Candidate findCandidate(Long id) {
        return candidateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Candidate not found"));
    }
}
