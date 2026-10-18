package com.janemichael.jmrecruitingcrm.search;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SearchRequest(
        @NotNull(message = "Company is required")
        Long companyId,

        Long hiringManagerId,

        @NotBlank(message = "Role title is required")
        @Size(max = 255, message = "Role title must be 255 characters or fewer")
        String title,

        @NotNull(message = "Track is required")
        SearchEnums.Track track,

        SearchEnums.Status status,

        SearchEnums.EngagementType engagementType,

        @DecimalMin(value = "0", message = "Fee must be between 0 and 100 percent")
        @DecimalMax(value = "100", message = "Fee must be between 0 and 100 percent")
        BigDecimal feePercent,

        @PositiveOrZero(message = "Base salary minimum can't be negative")
        Integer baseMin,

        @PositiveOrZero(message = "Base salary maximum can't be negative")
        Integer baseMax,

        @Size(max = 255, message = "Location must be 255 characters or fewer")
        String location,

        SearchEnums.WorkModel workModel,

        SearchEnums.Timeline timeline,

        LocalDate openedDate,

        @Size(max = 2000, message = "Production outcome must be 2000 characters or fewer")
        String productionOutcome,

        @Size(max = 2000, message = "Technical scope must be 2000 characters or fewer")
        String technicalScope,

        @Size(max = 2000, message = "Accountability must be 2000 characters or fewer")
        String accountability,

        @Size(max = 2000, message = "Regulatory constraints must be 2000 characters or fewer")
        String regulatoryConstraints,

        @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
        String notes,

        @Size(max = SearchRequest.JOB_DESCRIPTION_MAX, message = "The job description must be 10,000 characters or fewer")
        String jobDescription,

        Long ownerId
) {

    public static final int JOB_DESCRIPTION_MAX = 10_000;
}
