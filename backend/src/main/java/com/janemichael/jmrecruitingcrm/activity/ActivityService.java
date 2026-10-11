package com.janemichael.jmrecruitingcrm.activity;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.search.Search;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ActivityService {

    public static final int MAX_RECENT = 200;

    private final ActivityRepository activityRepository;
    private final ActivityLogger activityLogger;
    private final CompanyRepository companyRepository;
    private final SearchRepository searchRepository;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    public ActivityService(
            ActivityRepository activityRepository,
            ActivityLogger activityLogger,
            CompanyRepository companyRepository,
            SearchRepository searchRepository,
            CurrentUserService currentUserService,
            Clock clock
    ) {
        this.activityRepository = activityRepository;
        this.activityLogger = activityLogger;
        this.companyRepository = companyRepository;
        this.searchRepository = searchRepository;
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    /** The activity history for one record, newest first. With no filter, the team's latest activity. */
    public List<ActivityResponse> list(Long contactId, Long candidateId, Long searchId, Long companyId, int limit) {
        List<Activity> activities;

        if (contactId != null) {
            activities = activityRepository.findForContact(contactId);
        } else if (candidateId != null) {
            activities = activityRepository.findForCandidate(candidateId);
        } else if (searchId != null) {
            activities = activityRepository.findForSearch(searchId);
        } else if (companyId != null) {
            activities = activityRepository.findForCompany(companyId);
        } else {
            int size = Math.max(1, Math.min(limit, MAX_RECENT));
            activities = activityRepository.findRecent(PageRequest.of(0, size));
        }

        return activities.stream().map(ActivityResponse::from).toList();
    }

    /** Logs a note, call or meeting about a company or a search. */
    @Transactional
    public ActivityResponse logNote(NoteRequest request) {
        AppUser user = currentUserService.get();
        ActivityType type = request.type() == null ? ActivityType.NOTE : request.type();

        if (type != ActivityType.NOTE && type != ActivityType.CALL && type != ActivityType.MEETING
                && type != ActivityType.EMAIL) {
            throw new BadRequestException("Log replies and touches from the contact or candidate page");
        }

        if (request.searchId() != null) {
            Search search = searchRepository.findWithDetailsById(request.searchId())
                    .orElseThrow(() -> new NotFoundException("Search not found"));
            return ActivityResponse.from(activityLogger.forSearch(search, type, request.summary(), user));
        }

        if (request.companyId() != null) {
            Company company = companyRepository.findById(request.companyId())
                    .orElseThrow(() -> new NotFoundException("Company not found"));
            updateCompanyFollowUp(company, type, request);
            return ActivityResponse.from(activityLogger.forCompany(company, type, request.summary(), user));
        }

        throw new BadRequestException("Choose a company or a search for this note");
    }

    /**
     * A call, email or meeting with a company counts as contact and completes a follow-up that was due.
     * A new follow-up date can be set at the same time.
     */
    private void updateCompanyFollowUp(Company company, ActivityType type, NoteRequest request) {
        LocalDate today = LocalDate.now(clock);
        boolean contact = type == ActivityType.CALL || type == ActivityType.EMAIL || type == ActivityType.MEETING;

        if (contact) {
            company.setLastContactedDate(today);
        }
        if (request.followUpDate() != null) {
            company.setFollowUpDate(request.followUpDate());
        } else if (contact && company.getFollowUpDate() != null && !company.getFollowUpDate().isAfter(today)) {
            company.setFollowUpDate(null);
        }
    }

    public record NoteRequest(
            ActivityType type,

            @NotBlank(message = "Write something to log")
            @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
            String summary,

            Long companyId,

            Long searchId,

            /** For company notes: the next follow-up date. */
            LocalDate followUpDate
    ) {
    }
}
