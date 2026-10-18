package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.activity.Activity;
import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.activity.LogActivityRequest;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.EnumCsv;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.Later;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.RecentChanges;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineRepository;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineStage;
import com.janemichael.jmrecruitingcrm.placement.PlacementRepository;
import com.janemichael.jmrecruitingcrm.search.Search;
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
public class CandidateService {

    private final CandidateRepository candidateRepository;
    private final PipelineRepository pipelineRepository;
    private final PlacementRepository placementRepository;
    private final SearchRepository searchRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;
    private final FieldEncryptor encryptor;
    private final Clock clock;
    private final TrashService trash;
    private final RecentChanges recentChanges;
    private final UndoLog undoLog;

    public CandidateService(
            CandidateRepository candidateRepository,
            PipelineRepository pipelineRepository,
            PlacementRepository placementRepository,
            SearchRepository searchRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger,
            FieldEncryptor encryptor,
            Clock clock,
            TrashService trash,
            RecentChanges recentChanges,
            UndoLog undoLog
    ) {
        this.undoLog = undoLog;
        this.trash = trash;
        this.recentChanges = recentChanges;
        this.candidateRepository = candidateRepository;
        this.pipelineRepository = pipelineRepository;
        this.placementRepository = placementRepository;
        this.searchRepository = searchRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    public List<CandidateResponse> list(
            String q,
            CandidateEnums.Status status,
            SearchEnums.Track track,
            Long ownerId,
            boolean dueOnly
    ) {
        LocalDate today = LocalDate.now(clock);
        String query = Text.key(q);
        Map<Long, Long> activeCounts = activeSearchCounts();

        return candidateRepository.findAllWithOwner()
                .stream()
                .filter(candidate -> status == null || candidate.getStatus() == status)
                .filter(candidate -> track == null || candidate.getTrack() == track)
                .filter(candidate -> ownerId == null
                        || (candidate.getOwner() != null && candidate.getOwner().getId().equals(ownerId)))
                .filter(candidate -> !dueOnly || isDue(candidate, today))
                .filter(candidate -> query == null || matches(candidate, query))
                .sorted(Comparator.comparing((Candidate candidate) -> candidate.getName().toLowerCase()))
                .map(candidate -> CandidateResponse.summary(candidate,
                        activeCounts.getOrDefault(candidate.getId(), 0L)))
                .toList();
    }

    public CandidateResponse get(Long id) {
        Candidate candidate = find(id);
        return CandidateResponse.from(candidate, activeSearchCounts().getOrDefault(id, 0L));
    }

    @Transactional
    public CandidateResponse create(CandidateRequest request) {
        AppUser user = currentUserService.get();

        String email = Text.clean(request.email());
        if (email != null && candidateRepository.existsByEmailHash(encryptor.blindIndex(email))) {
            throw new ConflictException("A candidate with this email already exists");
        }

        Candidate candidate = new Candidate();
        apply(request, candidate);
        candidate.setStatus(request.status() == null ? CandidateEnums.Status.ACTIVE : request.status());
        candidate.setOwner(request.ownerId() == null ? user : currentUserService.findOwner(request.ownerId()));

        Candidate saved = candidateRepository.save(candidate);
        undoLog.record("Added " + saved.getName(), UndoLog.Kind.CANDIDATE, saved.getId());
        return CandidateResponse.from(saved, 0L);
    }

    @Transactional
    public CandidateResponse update(Long id, CandidateRequest request) {
        Candidate candidate = find(id);

        String email = Text.clean(request.email());
        if (email != null && candidateRepository.existsByEmailHashAndIdNot(encryptor.blindIndex(email), id)) {
            throw new ConflictException("A candidate with this email already exists");
        }

        CandidateEnums.Status previousStatus = candidate.getStatus();

        apply(request, candidate);
        if (request.status() != null) {
            candidate.setStatus(request.status());
        }
        candidate.setOwner(currentUserService.findOwner(request.ownerId()));

        if (previousStatus != candidate.getStatus()) {
            activityLogger.forCandidate(candidate, null, ActivityType.STATUS_CHANGE, false,
                    "Status changed from " + Labels.of(previousStatus) + " to " + Labels.of(candidate.getStatus()),
                    currentUserService.get());
        }

        undoLog.record("Edited " + candidate.getName(), UndoLog.Kind.CANDIDATE, id);
        return CandidateResponse.from(candidate, activeSearchCounts().getOrDefault(id, 0L));
    }

    @Transactional
    public void delete(Long id) {
        if (placementRepository.existsByCandidateId(id)) {
            throw new ConflictException("This candidate has a placement. Delete the placement first.");
        }
        candidateRepository.findById(id).ifPresent(candidate ->
                undoLog.record("Deleted " + candidate.getName(), UndoLog.Kind.CANDIDATE, id));
        trash.capture(TrashKind.CANDIDATE, id);
        if (candidateRepository.deleteCandidateById(id) == 0) {
            throw new NotFoundException("Candidate not found");
        }
    }

    /** Logs outreach, a reply, a meeting or a note, optionally tied to a search. */
    @Transactional
    public CandidateResponse logActivity(Long id, LogActivityRequest request) {
        Candidate candidate = find(id);
        AppUser user = currentUserService.get();
        ActivityType type = request.type();
        LocalDate today = LocalDate.now(clock);
        FollowUpState before = FollowUpState.of(candidate);

        if (!type.isManual()) {
            throw new BadRequestException("That activity type is recorded automatically");
        }
        if (type.isOutreach() && candidate.getStatus() == CandidateEnums.Status.DO_NOT_CONTACT) {
            throw new ConflictException("This candidate is marked Do not contact");
        }

        Search search = null;
        if (request.searchId() != null) {
            search = searchRepository.findWithDetailsById(request.searchId())
                    .orElseThrow(() -> new NotFoundException("Search not found"));
        }

        if (type.isOutreach() || type == ActivityType.MEETING) {
            candidate.setLastContactedDate(today);
        }

        if (request.clearsNextAction()) {
            candidate.setNextActionDate(null);
            candidate.setNextActionType(null);
        } else if (request.nextActionDate() != null) {
            candidate.setNextActionDate(request.nextActionDate());
            candidate.setNextActionType(request.nextActionType() != null
                    ? request.nextActionType()
                    : type.toActionType());
        } else if (type == ActivityType.REPLY) {
            candidate.setNextActionDate(today);
            candidate.setNextActionType(ActionType.LINKEDIN);
        } else if ((type.isOutreach() || type == ActivityType.MEETING)
                && candidate.getNextActionDate() != null
                && !candidate.getNextActionDate().isAfter(today)) {
            candidate.setNextActionDate(null);
            candidate.setNextActionType(null);
        }

        Activity activity = activityLogger.forCandidate(
                candidate, search, type, type.isOutreach(), request.notes(), user);
        rememberChange(candidate, user, activity, before);
        // A call saved from notes is this too, with what it changed on the record.
        undoLog.record(type.logged(candidate.getName()), UndoLog.Kind.CANDIDATE, id);

        return CandidateResponse.from(candidate, activeSearchCounts().getOrDefault(id, 0L));
    }

    /**
     * Saves a call written up from notes: logs it with its next step, then makes the changes it
     * brought to the record. Logged first, so a call where they ask not to be contacted again is
     * still recorded as the call it was.
     */
    @Transactional
    public CandidateResponse saveCall(Long id, CallUpdate update) {
        if (!CallUpdate.allowed(update.type())) {
            throw new BadRequestException("Save it as a call or a meeting.");
        }
        logActivity(id, new LogActivityRequest(update.type(), update.summary().trim(), update.nextActionDate(),
                update.nextActionDate() == null ? null : update.nextActionType(), false, update.searchId()));

        Candidate candidate = find(id);
        CandidateEnums.Status previousStatus = candidate.getStatus();
        if (update.status() != null) {
            candidate.setStatus(update.status());
        }
        if (Text.clean(update.currentTitle()) != null) {
            candidate.setCurrentTitle(Text.clean(update.currentTitle()));
        }
        if (Text.clean(update.currentCompany()) != null) {
            candidate.setCurrentCompany(Text.clean(update.currentCompany()));
        }
        if (Text.clean(update.location()) != null) {
            candidate.setLocation(Text.clean(update.location()));
        }
        if (update.workPreference() != null) {
            candidate.setWorkPreference(update.workPreference());
        }
        if (update.willingToRelocate() != null) {
            candidate.setWillingToRelocate(update.willingToRelocate());
        }
        if (update.employmentType() != null) {
            candidate.setEmploymentType(update.employmentType());
        }
        if (Text.clean(update.compExpectation()) != null) {
            candidate.setCompExpectation(Text.clean(update.compExpectation()));
        }
        String evidence = Text.clean(update.addEvidence());
        if (evidence != null) {
            String existing = Text.clean(candidate.getProductionEvidence());
            String combined = existing == null ? evidence : existing + "\n" + evidence;
            if (combined.length() > 10_000) {
                throw new BadRequestException("Production evidence would run over 10,000 characters. Untick the new "
                        + "evidence, or shorten what's on the record first.");
            }
            candidate.setProductionEvidence(combined);
        }

        if (previousStatus != candidate.getStatus()) {
            activityLogger.forCandidate(candidate, null, ActivityType.STATUS_CHANGE, false,
                    "Status changed from " + Labels.of(previousStatus) + " to " + Labels.of(candidate.getStatus()),
                    currentUserService.get());
        }
        return CandidateResponse.from(candidate, activeSearchCounts().getOrDefault(id, 0L));
    }

    /** Moves the next follow-up to the next business day or a week out, from the Today queue. */
    @Transactional
    public CandidateResponse snooze(Long id, Later until) {
        Candidate candidate = find(id);
        if (candidate.getNextActionDate() == null) {
            throw new ConflictException(candidate.getName() + " has no follow-up to move.");
        }

        AppUser user = currentUserService.get();
        FollowUpState before = FollowUpState.of(candidate);
        LocalDate date = until.from(LocalDate.now(clock));
        candidate.setNextActionDate(date);

        Activity activity = activityLogger.forCandidate(candidate, null, ActivityType.STATUS_CHANGE, false,
                "Next follow-up moved to " + Later.day(date), user);
        rememberChange(candidate, user, activity, before);
        undoLog.record("Moved " + candidate.getName() + "'s follow-up to " + Later.day(date),
                UndoLog.Kind.CANDIDATE, id);

        return CandidateResponse.from(candidate, activeSearchCounts().getOrDefault(id, 0L));
    }

    /** Undoes your last follow-up or snooze on this candidate from the last few minutes. */
    @Transactional
    public CandidateResponse undo(Long id) {
        Candidate candidate = find(id);
        RecentChanges.Change<FollowUpState> change = recentChanges.take(
                RecentChanges.Kind.CANDIDATE, id, currentUserService.get().getId(), FollowUpState.class);

        if (!FollowUpState.of(candidate).equals(change.after())) {
            throw new ConflictException(candidate.getName() + " changed after that, so it can't be undone.");
        }

        change.before().applyTo(candidate);
        activityLogger.remove(change.activityId());

        return CandidateResponse.from(candidate, activeSearchCounts().getOrDefault(id, 0L));
    }

    private void rememberChange(Candidate candidate, AppUser user, Activity activity, FollowUpState before) {
        recentChanges.remember(RecentChanges.Kind.CANDIDATE, candidate.getId(), user.getId(), activity.getId(),
                before, FollowUpState.of(candidate));
    }

    /** The parts of a candidate that logging a follow-up or moving it changes. */
    record FollowUpState(LocalDate lastContactedDate, LocalDate nextActionDate, ActionType nextActionType) {
        static FollowUpState of(Candidate candidate) {
            return new FollowUpState(candidate.getLastContactedDate(), candidate.getNextActionDate(),
                    candidate.getNextActionType());
        }

        void applyTo(Candidate candidate) {
            candidate.setLastContactedDate(lastContactedDate);
            candidate.setNextActionDate(nextActionDate);
            candidate.setNextActionType(nextActionType);
        }
    }

    public static boolean isDue(Candidate candidate, LocalDate today) {
        return candidate.getNextActionDate() != null
                && !candidate.getNextActionDate().isAfter(today)
                && candidate.getStatus() != CandidateEnums.Status.DO_NOT_CONTACT;
    }

    private Map<Long, Long> activeSearchCounts() {
        List<PipelineStage> activeStages = Arrays.stream(PipelineStage.values())
                .filter(PipelineStage::isActive)
                .toList();

        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : pipelineRepository.countByCandidateAndStageIn(activeStages)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static boolean matches(Candidate candidate, String query) {
        return contains(candidate.getName(), query)
                || contains(candidate.getEmail(), query)
                || contains(candidate.getCurrentTitle(), query)
                || contains(candidate.getCurrentCompany(), query)
                || contains(candidate.getLocation(), query)
                || contains(candidate.getProductionEvidence(), query)
                || contains(candidate.getNotes(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private static void apply(CandidateRequest request, Candidate candidate) {
        candidate.setName(request.name().trim());
        candidate.setEmail(Text.clean(request.email()));
        candidate.setPhone(Text.clean(request.phone()));
        candidate.setLinkedinUrl(Text.clean(request.linkedinUrl()));
        candidate.setLocation(Text.clean(request.location()));
        candidate.setCurrentTitle(Text.clean(request.currentTitle()));
        candidate.setCurrentCompany(Text.clean(request.currentCompany()));
        candidate.setTrack(request.track());
        candidate.setSource(request.source());
        candidate.setRegulatedExperience(Boolean.TRUE.equals(request.regulatedExperience()));
        candidate.setRoleAreas(EnumCsv.join(request.roleAreas()));
        candidate.setWorkPreference(request.workPreference());
        candidate.setExperience(request.experience());
        candidate.setWillingToRelocate(Boolean.TRUE.equals(request.willingToRelocate()));
        candidate.setEmploymentType(request.employmentType());
        candidate.setCompExpectation(Text.clean(request.compExpectation()));
        candidate.setResumeUrl(Text.clean(request.resumeUrl()));
        candidate.setProductionEvidence(Text.clean(request.productionEvidence()));
        candidate.setLastContactedDate(request.lastContactedDate());
        candidate.setNextActionDate(request.nextActionDate());
        candidate.setNextActionType(request.nextActionDate() == null ? null : request.nextActionType());
        candidate.setNotes(Text.clean(request.notes()));
    }

    private Candidate find(Long id) {
        return candidateRepository.findWithOwnerById(id)
                .orElseThrow(() -> new NotFoundException("Candidate not found"));
    }
}
