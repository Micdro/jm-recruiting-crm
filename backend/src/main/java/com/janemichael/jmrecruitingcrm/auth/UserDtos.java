package com.janemichael.jmrecruitingcrm.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes for users and passwords. */
public final class UserDtos {

    /** Long enough that a stolen database can't be cracked to unlock the CRM key (see KeyWrap). */
    public static final int MIN_PASSWORD_LENGTH = 12;

    public static final String PASSWORD_TOO_SHORT = "Password must be at least " + MIN_PASSWORD_LENGTH + " characters";

    private UserDtos() {
    }

    public record UserResponse(Long id, String email, String displayName, UserRole role, boolean active) {

        public static UserResponse from(AppUser user) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getRole(),
                    user.isActive()
            );
        }
    }

    /** The signed-in person, and whether this computer's CRM is still locked for them. */
    public record SignedInUser(
            Long id, String email, String displayName, UserRole role, boolean active, boolean crmLocked) {

        public static SignedInUser from(AppUser user, boolean crmLocked) {
            return new SignedInUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                    user.isActive(), crmLocked);
        }
    }

    public record CreateUserRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be valid")
            @Size(max = 255, message = "Email must be 255 characters or fewer")
            String email,

            @NotBlank(message = "Name is required")
            @Size(max = 100, message = "Name must be 100 characters or fewer")
            String displayName,

            @NotNull(message = "Role is required")
            UserRole role,

            @NotBlank(message = "Password is required")
            @Size(min = MIN_PASSWORD_LENGTH, max = 200, message = PASSWORD_TOO_SHORT)
            String password
    ) {
    }

    public record UpdateUserRequest(
            @NotBlank(message = "Name is required")
            @Size(max = 100, message = "Name must be 100 characters or fewer")
            String displayName,

            @NotNull(message = "Role is required")
            UserRole role,

            @NotNull(message = "Choose whether the account is active")
            Boolean active
    ) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "Password is required")
            @Size(min = MIN_PASSWORD_LENGTH, max = 200, message = PASSWORD_TOO_SHORT)
            String password
    ) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required")
            String currentPassword,

            @NotBlank(message = "New password is required")
            @Size(min = MIN_PASSWORD_LENGTH, max = 200, message = "New password must be at least " + MIN_PASSWORD_LENGTH + " characters")
            String newPassword
    ) {
    }
}
