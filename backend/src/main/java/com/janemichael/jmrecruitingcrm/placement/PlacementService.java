package com.janemichael.jmrecruitingcrm.placement;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.candidate.CandidateEnums;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineEntry;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineRepository;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineService;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineStage;
import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.CheckInRequest;
import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.InvoiceRequest;
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
import java.time.Clock;
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
    private final Clock clock;

    public PlacementService(
            PlacementRepository placementRepository,
            SearchRepository searchRepository,
            CandidateRepository candidateRepository,
            PipelineRepository pipelineRepository,
            PipelineService pipelineService,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            TrashService trash,
            Clock clock
    ) {
        this.trash = trash;
        this.clock = clock;
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
                .map(placement -> PlacementResponse.from(placement, LocalDate.now(clock)))
                .toList();
    }

    public PlacementResponse get(Long id) {
        return PlacementResponse.from(find(id), LocalDate.now(clock));
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

        return PlacementResponse.from(saved, LocalDate.now(clock));
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
        return PlacementResponse.from(placement, LocalDate.now(clock));
    }

    /**
     * Records the invoice for a placement (its number, dates, who it's billed to and the line on it)
     * and marks the placement invoiced. The amount is the placement's fee.
     */
    @Transactional
    public PlacementResponse invoice(Long id, InvoiceRequest request) {
        Placement placement = find(id);
        if (placement.getFeeAmount() == null) {
            throw new BadRequestException("Add the fee to the placement first");
        }
        if (request.dueDate().isBefore(request.invoiceDate())) {
            throw new BadRequestException("The due date can't be before the invoice date");
        }
        String number = request.invoiceNumber().trim();
        if (placementRepository.existsByInvoiceNumberAndIdNot(number, id)) {
            throw new ConflictException("Invoice " + number + " is already used. Use the next number.");
        }
        placement.setInvoiceNumber(number);
        placement.setInvoiceDate(request.invoiceDate());
        placement.setInvoiceDueDate(request.dueDate());
        placement.setBillToName(Text.clean(request.billToName()));
        placement.setBillToEmail(Text.clean(request.billToEmail()));
        placement.setBillToAddress(Text.clean(request.billToAddress()));
        placement.setInvoiceDescription(Text.clean(request.description()));
        if (placement.getInvoiceStatus() == Placement.InvoiceStatus.NOT_INVOICED) {
            placement.setInvoiceStatus(Placement.InvoiceStatus.INVOICED);
        }
        return PlacementResponse.from(placement, LocalDate.now(clock));
    }

    /** The next invoice number for this year: 2026-001, 2026-002 and so on, after the highest used. */
    /**
     * Records a guarantee check-in with the candidate and the client, with notes for the history.
     * It covers every check-in point that has come, so Today stops asking until the next one.
     */
    @Transactional
    public PlacementResponse checkIn(Long id, CheckInRequest request) {
        AppUser user = currentUserService.get();
        Placement placement = find(id);
        LocalDate today = LocalDate.now(clock);
        GuaranteeCheckIns.CheckIn due = GuaranteeCheckIns.next(placement, today)
                .filter(GuaranteeCheckIns.CheckIn::due)
                .orElseThrow(() -> new ConflictException("No guarantee check-in is due for this placement"));

        placement.setCheckedInOn(today);
        Candidate candidate = placement.getCandidate();
        candidate.setLastContactedDate(today);
        String notes = Text.clean(request.notes());
        String summary = due.days() + "-day guarantee check-in with " + candidate.getName() + " and "
                + placement.getSearch().getCompany().getName();
        activityLogger.forCandidate(candidate, placement.getSearch(), ActivityType.NOTE, false,
                notes == null ? summary : summary + ": " + notes, user);
        return PlacementResponse.from(placement, today);
    }

    /** Placements with a guarantee check-in due today, for Today. */
    public List<PlacementResponse> checkInsDue() {
        LocalDate today = LocalDate.now(clock);
        return placementRepository.findAllWithDetails().stream()
                .map(placement -> PlacementResponse.from(placement, today))
                .filter(PlacementResponse::checkInDue)
                .sorted(java.util.Comparator.comparing(PlacementResponse::checkInDate))
                .toList();
    }

    public String nextInvoiceNumber() {
        String prefix = LocalDate.now(clock).getYear() + "-";
        int highest = 0;
        for (String number : placementRepository.findInvoiceNumbersStartingWith(prefix)) {
            String rest = number.substring(prefix.length());
            if (rest.matches("\\d{1,6}")) {
                highest = Math.max(highest, Integer.parseInt(rest));
            }
        }
        return prefix + String.format("%03d", highest + 1);
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
