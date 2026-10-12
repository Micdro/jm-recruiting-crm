package com.janemichael.jmrecruitingcrm.template;

import com.janemichael.jmrecruitingcrm.common.ActionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request and response shapes for message templates. */
public final class TemplateDtos {

    private TemplateDtos() {
    }

    public record TemplateRequest(
            @NotBlank(message = "Name is required")
            @Size(max = 100, message = "Name must be 100 characters or fewer")
            String name,

            @NotNull(message = "Choose LinkedIn or email")
            ActionType channel,

            @Min(value = 1, message = "Touch must be 1 to 4")
            @Max(value = 4, message = "Touch must be 1 to 4")
            Integer touch,

            @Size(max = 200, message = "Subject must be 200 characters or fewer")
            String subject,

            @NotBlank(message = "Message is required")
            @Size(max = 5000, message = "Message must be 5,000 characters or fewer")
            String body
    ) {
    }

    public record TemplateResponse(
            Long id,
            String name,
            ActionType channel,
            Integer touch,
            String subject,
            String body,
            Long ownerId,
            String ownerName,
            Instant updatedAt
    ) {

        public static TemplateResponse from(MessageTemplate template) {
            return new TemplateResponse(
                    template.getId(),
                    template.getName(),
                    template.getChannel(),
                    template.getTouch(),
                    template.getSubject(),
                    template.getBody(),
                    template.getOwner() == null ? null : template.getOwner().getId(),
                    template.getOwner() == null ? null : template.getOwner().getDisplayName(),
                    template.getUpdatedAt()
            );
        }
    }
}
