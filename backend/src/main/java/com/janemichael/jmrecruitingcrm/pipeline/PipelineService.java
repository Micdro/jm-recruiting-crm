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
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.PipelineEntryResponse;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.UpdateEntryRequest;
import com.janemichael.jmrecruitingcrm.placement.PlacementRepository;
import com.janemichael.jmrecruitingcrm.search.Search;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
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
                        placedCandidateIds.contains(entry.getCandidate().getId())))
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
                        placedSearchIds.contains(entry.getSearch().getId())))
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
        return PipelineEntryResponse.from(entry, placed);
    }

    @Transactional
    public PipelineEntryResponse update(Long id, UpdateEntryRequest request) {
        AppUser user = currentUserService.get();
        PipelineEntry entry = find(id);

        entry.setRationale(Text.clean(request.rationale()));
        changeStage(entry, request.stage(), user);

        boolean placed = placementRepository.existsBySearchIdAndCandidateId(
                entry.getSearch().getId(), entry.getCandidate().getId());
        return PipelineEntryResponse.from(entry, placed);
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

    private PipelineEntry find(Long id) {
        return pipelineRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Pipeline entry not found"));
    }
}
