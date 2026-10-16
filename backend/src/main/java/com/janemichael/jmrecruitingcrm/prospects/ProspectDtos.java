package com.janemichael.jmrecruitingcrm.prospects;

import com.janemichael.jmrecruitingcrm.company.CompanyEnums.HiringNeed;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums.Industry;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums.Segment;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** What the AI Found page and the card on Today send and receive. */
public final class ProspectDtos {

    private ProspectDtos() {
    }

    /**
     * The finder's setup and state.
     *
     * @param keyHint     the API key's last four characters
     * @param runsOn      the name of the computer that runs it each weekday
     * @param runsHere    whether that's this computer
     * @param waiting     companies found and not reviewed yet
     * @param canEdit     whether the signed-in person can change the schedule and guidance (admins)
     * @param canEditLimits whether they can change the API key and the spending limits (the account owner)
     * @param ownerName   the account owner, who sets the key and the limits
     */
    public record Status(
            boolean configured,
            String keyHint,
            boolean enabled,
            int perRun,
            String focus,
            int dailyLimitDollars,
            int spentTodayCents,
            int monthlyLimitDollars,
            int spentThisMonthCents,
            String runsOn,
            boolean runsHere,
            boolean running,
            LastRun lastRun,
            long waiting,
            boolean canEdit,
            boolean canEditLimits,
            String ownerName
    ) {
    }

    /** The most recent run, from any computer. Status is RUNNING, DONE or FAILED. */
    public record LastRun(
            String status,
            String trigger,
            Instant startedAt,
            Instant finishedAt,
            String computer,
            String startedBy,
            int found,
            int added,
            int skipped,
            int contacts,
            int withheld,
            int searches,
            int costCents,
            String message
    ) {
    }

    public record KeyRequest(
            @NotBlank(message = "Paste the API key")
            @Size(max = 300, message = "That doesn't look like an API key")
            String apiKey
    ) {
    }

    public record SettingsRequest(
            @NotNull(message = "Choose whether it runs each weekday")
            Boolean enabled,

            @NotNull(message = "Enter how many companies to find each run")
            @Min(value = 1, message = "Find at least 1 company each run")
            @Max(value = ProspectSettings.MAX_PER_RUN, message = "Find at most 25 companies each run")
            Integer perRun,

            @Size(max = 2000, message = "Keep the guidance to 2,000 characters or fewer")
            String focus
    ) {
    }

    /** What the finder may spend. A search stops when either limit would be passed. */
    public record LimitsRequest(
            @NotNull(message = "Enter a daily limit")
            @Min(value = 1, message = "The daily limit must be at least $1, what one search needs")
            @Max(value = 100, message = "The daily limit can be up to $100")
            Integer dailyLimitDollars,

            @NotNull(message = "Enter a monthly limit")
            @Min(value = 0, message = "The monthly limit can't be negative")
            @Max(value = 1000, message = "The monthly limit can be up to $1,000")
            Integer monthlyLimitDollars
    ) {
    }

    /** A company waiting on the AI Found page, with the people found with it. */
    public record FoundCompany(
            Long id,
            String name,
            String website,
            String location,
            String focus,
            Industry industry,
            List<Segment> segments,
            HiringNeed hiringNeed,
            String notes,
            Instant foundAt,
            List<FoundContact> contacts
    ) {
    }

    public record FoundContact(Long id, String name, String title, String linkedinUrl, String email, String phone) {
    }

    /**
     * Asks for a draft of the next message to a contact.
     *
     * @param touch   which touch of the cold sequence it is (1 to 4), or null outside a sequence
     * @param role    the open role it's about, when writing from a hiring signal
     * @param example the team's own template for this step, filled in, to keep its voice
     */
    public record DraftRequest(
            @NotNull(message = "Choose a contact")
            Long contactId,

            @NotBlank(message = "Choose LinkedIn or email")
            @jakarta.validation.constraints.Pattern(regexp = "LINKEDIN|EMAIL", message = "Choose LinkedIn or email")
            String channel,

            @Min(value = 1, message = "The touch must be between 1 and 4")
            @Max(value = 4, message = "The touch must be between 1 and 4")
            Integer touch,

            @Size(max = 200, message = "The role must be 200 characters or fewer")
            String role,

            @Size(max = 4000, message = "The example must be 4,000 characters or fewer")
            String example
    ) {
    }

    /** A draft to read and edit. {@code basis} is what it was written from; subject is for email only. */
    public record DraftResponse(String subject, String body, List<String> basis, int costCents) {
    }

    /** For the card on Today. */
    public record Summary(long waiting, boolean running, LastRun lastRun) {
    }

    public record Reviewed(Long id, String name, CompanyStatus status, int contactsRemoved) {
    }
}
