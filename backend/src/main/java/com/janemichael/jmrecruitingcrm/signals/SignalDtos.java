package com.janemichael.jmrecruitingcrm.signals;

import com.janemichael.jmrecruitingcrm.company.CompanyEnums;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What the hiring signals API sends and accepts. */
public final class SignalDtos {

    private SignalDtos() {
    }

    /** A job running on this computer: reading boards ({@code CHECK}) or looking for them ({@code FIND}). */
    public record Job(String kind, int done, int total) {
    }

    public record Overview(
            Job running,
            /** When this computer last read a board. */
            Instant lastCheckedAt,
            int withBoard,
            /** Companies with no board on file that the finder may still look for. */
            int withoutBoard,
            /** Companies marked as having no board the CRM can read. */
            int noBoard,
            /** Boards found automatically that nobody has confirmed. */
            int toConfirm,
            /** Boards that couldn't be read last time. */
            int failing
    ) {
    }

    /**
     * An open role the firm places. {@code unreviewed}: posted since the team last reviewed the company
     * (and within the last 30 days).
     */
    public record Role(
            String title,
            String location,
            String url,
            RoleTrack track,
            Instant publishedAt,
            boolean unreviewed
    ) {
    }

    /** Someone at the company to message about it. */
    public record Person(Long id, String name, String title, OutreachStatus status, LocalDate nextActionDate) {
    }

    /** A company with matching open roles. */
    public record CompanySignal(
            Long companyId,
            String name,
            CompanyStatus status,
            CompanyEnums.Tier tier,
            CompanyEnums.Priority priority,
            String ownerName,
            JobBoardProvider provider,
            String boardUrl,
            boolean boardConfirmed,
            Instant reviewedAt,
            Integer openRoles,
            int unreviewedRoles,
            Instant latestPublishedAt,
            List<Role> roles,
            List<Person> people,
            int contactCount
    ) {
    }

    /** A company without a readable board, or whose board couldn't be read. */
    public record CompanyRef(
            Long companyId,
            String name,
            CompanyStatus status,
            JobBoardProvider provider,
            String boardUrl,
            Instant searchedAt,
            String error
    ) {
    }

    public record SignalsResponse(
            Overview overview,
            List<CompanySignal> companies,
            List<CompanyRef> withoutBoard,
            List<CompanyRef> failing
    ) {
    }

    /** The answer to starting a job: whether it started, for how many companies, and where things stand. */
    public record JobStarted(boolean started, int companies, Overview overview) {
    }

    /** For Today: companies with roles nobody has reviewed yet. */
    public record SummaryItem(Long companyId, String name, CompanyStatus status, int unreviewedRoles, String firstRole) {
    }

    public record Summary(int companies, List<SummaryItem> top, Job running) {
    }

    /** One company's board, for its page. */
    public record CompanyBoard(
            Long companyId,
            JobBoardProvider provider,
            String token,
            String boardUrl,
            boolean confirmed,
            Instant checkedAt,
            Integer openRoles,
            String error,
            Instant searchedAt,
            Instant reviewedAt,
            List<Role> roles
    ) {
    }

    /**
     * Sets a company's board from a pasted link. {@code none}: the company has no board the CRM can
     * read (also how a wrong board found automatically is turned down). Neither: clears it, so the
     * finder can look again.
     */
    public record BoardRequest(
            @Size(max = 500, message = "The link must be 500 characters or fewer")
            String link,
            boolean none
    ) {
    }
}
