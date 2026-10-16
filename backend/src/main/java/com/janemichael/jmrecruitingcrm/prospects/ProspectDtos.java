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
     * @param canEdit     whether the signed-in person can change the settings (admins)
     */
    public record Status(
            boolean configured,
            String keyHint,
            boolean enabled,
            int perRun,
            String focus,
            int monthlyLimitDollars,
            int spentThisMonthCents,
            String runsOn,
            boolean runsHere,
            boolean running,
            LastRun lastRun,
            long waiting,
            boolean canEdit
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
            String focus,

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

    /** For the card on Today. */
    public record Summary(long waiting, boolean running, LastRun lastRun) {
    }

    public record Reviewed(Long id, String name, CompanyStatus status, int contactsRemoved) {
    }
}
