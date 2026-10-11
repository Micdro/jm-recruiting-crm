package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CandidateRequest(
        @NotBlank(message = "Candidate name is required")
        @Size(max = 255, message = "Candidate name must be 255 characters or fewer")
        String name,

        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must be 255 characters or fewer")
        String email,

        @Size(max = 50, message = "Phone must be 50 characters or fewer")
        String phone,

        @Size(max = 500, message = "LinkedIn URL must be 500 characters or fewer")
        String linkedinUrl,

        @Size(max = 255, message = "Location must be 255 characters or fewer")
        String location,

        @Size(max = 255, message = "Current title must be 255 characters or fewer")
        String currentTitle,

        @Size(max = 255, message = "Current company must be 255 characters or fewer")
        String currentCompany,

        SearchEnums.Track track,

        CandidateEnums.Status status,

        CandidateEnums.Source source,

        Boolean regulatedExperience,

        @Size(max = 255, message = "Compensation expectation must be 255 characters or fewer")
        String compExpectation,

        @Size(max = 500, message = "Resume link must be 500 characters or fewer")
        String resumeUrl,

        @Size(max = 4000, message = "Production evidence must be 4000 characters or fewer")
        String productionEvidence,

        LocalDate lastContactedDate,

        LocalDate nextActionDate,

        ActionType nextActionType,

        @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
        String notes,

        Long ownerId
) {
}
