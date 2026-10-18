package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A call or meeting with a candidate, written up: what goes on the timeline, the next step, and
 * what it changed on their record. A field left null stays as it is.
 *
 * @param addEvidence production evidence from the call, added under what's there
 */
public record CallUpdate(
        @NotNull(message = "Choose call or meeting")
        ActivityType type,

        @NotBlank(message = "Write what the call covered")
        @Size(max = 2000, message = "The write-up must be 2,000 characters or fewer")
        String summary,

        CandidateEnums.Status status,

        @Size(max = 255, message = "Current title must be 255 characters or fewer")
        String currentTitle,

        @Size(max = 255, message = "Current company must be 255 characters or fewer")
        String currentCompany,

        @Size(max = 255, message = "Location must be 255 characters or fewer")
        String location,

        SearchEnums.WorkModel workPreference,

        Boolean willingToRelocate,

        CandidateEnums.EmploymentType employmentType,

        @Size(max = 255, message = "Compensation expectation must be 255 characters or fewer")
        String compExpectation,

        @Size(max = 5000, message = "New evidence must be 5,000 characters or fewer")
        String addEvidence,

        LocalDate nextActionDate,

        ActionType nextActionType,

        /** The search the call was about, if any. */
        Long searchId
) {

    /** Calls and meetings are what notes come from. */
    public static boolean allowed(ActivityType type) {
        return type == ActivityType.CALL || type == ActivityType.MEETING;
    }
}
