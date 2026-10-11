package com.janemichael.jmrecruitingcrm.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

        @Size(max = 500, message = "LinkedIn profile must be 500 characters or fewer")
        String linkedinProfile,

        @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
        String notes,

        Long ownerId
) {
}
