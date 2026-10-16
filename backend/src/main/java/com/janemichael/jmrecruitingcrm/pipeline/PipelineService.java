package com.janemichael.jmrecruitingcrm.pipeline;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.candidate.CandidateEnums;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.AddToSearchRequest;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.DebriefRequest;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.DebriefWith;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.InterviewRequest;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.PipelineEntryResponse;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.UpdateEntryRequest;
import com.janemichael.jmrecruitingcrm.placement.PlacementRepository;
import com.janemichael.jmrecruitingcrm.search.Search;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final SearchRepository searchRepository;
    private final CandidateRepository candidateRepository;
    private final PlacementRepository placementRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final Clock clock;

    public PipelineService(
            PipelineRepository pipelineRepository,
            SearchRepository searchRepository,
            CandidateRepository candidateRepository,
            PlacementRepository placementRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            Clock clock
    ) {
        this.pipelineRepository = pipelineRepository;
        this.searchRepository = searchRepository;
        this.candidateRepository = candidateRepository;
        this.placementRepository = placementRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
        this.clock = clock;
    }

    public List<PipelineEntryResponse> forSearch(Long searchId) {
        if (!searchRepository.existsById(searchId)) {
            throw new NotFoundException("Search not found");
        }
        Set<Long> placedCandidateIds = new HashSet<>(placementRepository.findCandidateIdsForSearch(searchId));

        return pipelineRepository.findForSearch(searchId)
                .stream()
                .map(entry -> PipelineEntryResponse.from(entry,
                        placedCandidateIds.contains(entry.getCandidate().getId()), now()))
                .toList();
    }

    public List<PipelineEntryResponse> forCandidate(Long candidateId) {
        if (!candidateRepository.existsById(candidateId)) {
            throw new NotFoundException("Candidate not found");
        }
        Set<Long> placedSearchIds = new HashSet<>(placementRepository.findSearchIdsForCandidate(candidateId));

        return pipelineRepository.findForCandidate(candidateId)
                .stream()
                .map(entry -> PipelineEntryResponse.from(entry,
                        placedSearchIds.contains(entry.getSearch().getId()), now()))
                .toList();
    }

    @Transactional
    public PipelineEntryResponse add(AddToSearchRequest request) {
        AppUser user = currentUserService.get();
        Search search = searchRepository.findWithDetailsById(request.searchId())
                .orElseThrow(() -> new NotFoundException("Search not found"));
        Candidate candidate = candidateRepository.findById(request.candidateId())
                .orElseThrow(() -> new NotFoundException("Candidate not found"));

        if (pipelineRepository.existsBySearchIdAndCandidateId(search.getId(), candidate.getId())) {
            throw new ConflictException("This candidate is already in this search");
        }

        PipelineEntry entry = createEntry(search, candidate,
                request.stage() == null ? PipelineStage.SOURCED : request.stage(),
                Text.clean(request.rationale()), user);

        boolean placed = placementRepository.existsBySearchIdAndCandidateId(search.getId(), candidate.getId());
        return PipelineEntryResponse.from(entry, placed, now());
    }

    @Transactional
    public PipelineEntryResponse update(Long id, UpdateEntryRequest request) {
        AppUser user = currentUserService.get();
        PipelineEntry entry = find(id);

        entry.setRationale(Text.clean(request.rationale()));
        changeStage(entry, request.stage(), user);

        boolean placed = placementRepository.existsBySearchIdAndCandidateId(
                entry.getSearch().getId(), entry.getCandidate().getId());
        return PipelineEntryResponse.from(entry, placed, now());
    }

    /**
     * Schedules the next interview, or moves the one scheduled. A new date or time starts a new
     * round, so both debriefs are due again after it. A candidate not yet interviewing moves to
     * Interviewing.
     */
    @Transactional
    public PipelineEntryResponse scheduleInterview(Long id, InterviewRequest request) {
        AppUser user = currentUserService.get();
        PipelineEntry entry = find(id);
        if (!entry.getStage().isActive()) {
            throw new ConflictException(entry.getCandidate().getName() + " is no longer in process in this search");
        }

        LocalTime time = request.time() == null ? null : request.time().withSecond(0).withNano(0);
        boolean moved = entry.getInterviewDate() != null;
        boolean newRound = !request.date().equals(entry.getInterviewDate()) || !Objects.equals(time, entry.getInterviewTime());
        String label = Text.clean(request.label());
        boolean relabelled = !Objects.equals(label, entry.getInterviewLabel());

        entry.setInterviewDate(request.date());
        entry.setInterviewTime(time);
        entry.setInterviewLabel(label);
        if (newRound) {
            entry.setCandidateDebriefed(false);
            entry.setClientDebriefed(false);
        }
        if (entry.getStage().ordinal() < PipelineStage.INTERVIEWING.ordinal()) {
            changeStage(entry, PipelineStage.INTERVIEWING, user);
        }

        if (newRound || relabelled) {
            activityLogger.forCandidate(entry.getCandidate(), entry.getSearch(), ActivityType.STAGE_CHANGE, false,
                    (moved && newRound ? "Interview moved to " : "Interview set for ")
                            + Interviews.describe(request.date(), time) + (label == null ? "" : ": " + label), user);
        }
        return response(entry);
    }

    /** Takes the interview off, when the client cancels it. */
    @Transactional
    public PipelineEntryResponse cancelInterview(Long id) {
        AppUser user = currentUserService.get();
        PipelineEntry entry = find(id);
        if (entry.getInterviewDate() == null) {
            throw new ConflictException("There's no interview to cancel");
        }

        activityLogger.forCandidate(entry.getCandidate(), entry.getSearch(), ActivityType.STAGE_CHANGE, false,
                "Interview on " + Interviews.describe(entry.getInterviewDate(), entry.getInterviewTime()) + " cancelled",
                user);
        entry.setInterviewDate(null);
        entry.setInterviewTime(null);
        entry.setInterviewLabel(null);
        entry.setCandidateDebriefed(false);
        entry.setClientDebriefed(false);
        return response(entry);
    }

    /**
     * Records a debrief after the interview, with the candidate or with the client, and the notes
     * from it. It goes in the candidate's, the search's and the company's history.
     */
    @Transactional
    public PipelineEntryResponse debrief(Long id, DebriefRequest request) {
        AppUser user = currentUserService.get();
        PipelineEntry entry = find(id);
        if (entry.getInterviewDate() == null) {
            throw new ConflictException("There's no interview to debrief");
        }

        Candidate candidate = entry.getCandidate();
        String when = Interviews.describe(entry.getInterviewDate(), entry.getInterviewTime());
        String summary;
        if (request.with() == DebriefWith.CANDIDATE) {
            entry.setCandidateDebriefed(true);
            candidate.setLastContactedDate(LocalDate.now(clock));
            summary = "Debriefed " + candidate.getName() + " after the interview on " + when;
        } else {
            entry.setClientDebriefed(true);
            summary = "Debriefed " + entry.getSearch().getCompany().getName() + " after " + candidate.getName()
                    + "'s interview on " + when;
        }
        String notes = Text.clean(request.notes());
        activityLogger.forCandidate(candidate, entry.getSearch(), ActivityType.NOTE, false,
                notes == null ? summary : summary + ": " + notes, user);
        return response(entry);
    }

    /** Interviews today that haven't finished, and debriefs due, for Today. */
    public List<PipelineEntryResponse> interviewsForToday() {
        LocalDateTime now = now();
        return pipelineRepository.findWithInterviewOnOrBefore(now.toLocalDate())
                .stream()
                .filter(entry -> Interviews.debriefDue(entry, now) || Interviews.isLaterToday(entry, now))
                .sorted(Comparator
                        .comparing((PipelineEntry entry) -> !Interviews.debriefDue(entry, now))
                        .thenComparing(PipelineEntry::getInterviewDate)
                        .thenComparing(entry -> entry.getInterviewTime() == null ? LocalTime.MAX : entry.getInterviewTime()))
                .map(entry -> PipelineEntryResponse.from(entry, false, now))
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        if (pipelineRepository.deleteEntryById(id) == 0) {
            throw new NotFoundException("Pipeline entry not found");
        }
    }

    /** Adds a candidate to a search and records it in their history. */
    @Transactional
    public PipelineEntry createEntry(Search search, Candidate candidate, PipelineStage stage, String rationale,
                                     AppUser user) {
        PipelineEntry entry = new PipelineEntry();
        entry.setSearch(search);
        entry.setCandidate(candidate);
        entry.setStage(stage);
        entry.setRationale(rationale);
        entry.setStageChangedAt(Instant.now(clock));
        PipelineEntry saved = pipelineRepository.save(entry);

        activityLogger.forCandidate(candidate, search, ActivityType.STAGE_CHANGE, false,
                "Added to " + search.getTitle() + " as " + Labels.of(stage), user);

        if (stage == PipelineStage.PLACED) {
            candidate.setStatus(CandidateEnums.Status.PLACED);
        }
        return saved;
    }

    /** Moves a candidate to a new stage, logs it, and marks them placed when they are. */
    @Transactional
    public void changeStage(PipelineEntry entry, PipelineStage stage, AppUser user) {
        if (entry.getStage() == stage) {
            return;
        }

        PipelineStage previous = entry.getStage();
        entry.setStage(stage);
        entry.setStageChangedAt(Instant.now(clock));

        activityLogger.forCandidate(entry.getCandidate(), entry.getSearch(), ActivityType.STAGE_CHANGE, false,
                entry.getSearch().getTitle() + ": " + Labels.of(previous) + " to " + Labels.of(stage), user);

        if (stage == PipelineStage.PLACED) {
            entry.getCandidate().setStatus(CandidateEnums.Status.PLACED);
        }
    }

    private PipelineEntryResponse response(PipelineEntry entry) {
        boolean placed = placementRepository.existsBySearchIdAndCandidateId(
                entry.getSearch().getId(), entry.getCandidate().getId());
        return PipelineEntryResponse.from(entry, placed, now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private PipelineEntry find(Long id) {
        return pipelineRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Pipeline entry not found"));
    }
}
