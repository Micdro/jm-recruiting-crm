package com.janemichael.jmrecruitingcrm.activity;

import com.janemichael.jmrecruitingcrm.common.ActionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Logs a touch, reply, meeting or note against a contact or candidate, and optionally
 * sets the next action. Leave the next action empty to use the defaults.
 */
public record LogActivityRequest(
        @NotNull(message = "Activity type is required")
        ActivityType type,

        @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
        String notes,

        LocalDate nextActionDate,

        ActionType nextActionType,

        /** Clears the next action instead of scheduling one. */
        boolean clearNextAction,

        /** For candidates: the search this activity relates to, if any. */
        Long searchId
) {
}
