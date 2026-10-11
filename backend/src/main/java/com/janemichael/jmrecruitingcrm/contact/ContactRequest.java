package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.common.ActionType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ContactRequest(
        @NotBlank(message = "Contact name is required")
        @Size(max = 255, message = "Contact name must be 255 characters or fewer")
        String name,

        @Size(max = 255, message = "Title must be 255 characters or fewer")
        String title,

        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must be 255 characters or fewer")
        String email,

        @Size(max = 50, message = "Phone must be 50 characters or fewer")
        String phone,

        @Size(max = 500, message = "LinkedIn URL must be 500 characters or fewer")
        String linkedinUrl,

        OutreachStatus status,

        LocalDate lastContactedDate,

        LocalDate nextActionDate,

        ActionType nextActionType,

        @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
        String notes,

        @NotNull(message = "Company ID is required")
        Long companyId,

        Long ownerId
) {
}
