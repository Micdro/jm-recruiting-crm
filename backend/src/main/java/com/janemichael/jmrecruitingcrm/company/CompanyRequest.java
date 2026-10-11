package com.janemichael.jmrecruitingcrm.company;

import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CompanyRequest(
        @NotBlank(message = "Company name is required")
        @Size(max = 255, message = "Company name must be 255 characters or fewer")
        String name,

        @Size(max = 500, message = "Website must be 500 characters or fewer")
        String website,

        @Size(max = 255, message = "Location must be 255 characters or fewer")
        String location,

        @Size(max = 100, message = "Company size must be 100 characters or fewer")
        String companySize,

        CompanyStatus status,

        @Size(max = 500, message = "Focus must be 500 characters or fewer")
        String focus,

        CompanyEnums.Industry industry,

        List<CompanyEnums.Segment> segments,

        CompanyEnums.Tier tier,

        CompanyEnums.Priority priority,

        CompanyEnums.HiringNeed hiringNeed,

        SearchEnums.Timeline hiringTimeline,

        @PositiveOrZero(message = "Anticipated hires can't be negative")
        Integer anticipatedHires,

        CompanyEnums.LeadSource leadSource,

        LocalDate lastContactedDate,

        LocalDate followUpDate,

        @Size(max = 500, message = "LinkedIn profile must be 500 characters or fewer")
        String linkedinProfile,

        @Size(max = 20000, message = "Notes must be 20,000 characters or fewer")
        String notes,

        Long ownerId
) {
}
