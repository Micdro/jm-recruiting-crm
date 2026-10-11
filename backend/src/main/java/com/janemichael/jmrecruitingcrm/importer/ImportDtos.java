package com.janemichael.jmrecruitingcrm.importer;

import com.janemichael.jmrecruitingcrm.candidate.CandidateEnums;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.BuyingRole;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * CSV imports, including ClickUp exports. The browser parses the file and maps columns; the
 * server validates each row, creates missing companies and skips or updates duplicates.
 * The owner column may hold a name, initials (MD, DD) or an email; anything unrecognised
 * is assigned to the person importing.
 */
public final class ImportDtos {

    public static final int MAX_ROWS = 5000;

    private ImportDtos() {
    }

    public enum DuplicateMode {
        /** Leave existing records alone. */
        SKIP,
        /** Fill in existing records with the values in the file. */
        UPDATE
    }

    public record CompanyImportRequest(
            @NotNull(message = "Rows are required")
            @Size(max = MAX_ROWS, message = "Import at most 5000 rows at a time")
            List<@Valid CompanyRow> rows,

            DuplicateMode duplicateMode
    ) {
    }

    public record CompanyRow(
            String name,
            String website,
            String location,
            String companySize,
            CompanyStatus status,
            String focus,
            CompanyEnums.Industry industry,
            List<CompanyEnums.Segment> segments,
            CompanyEnums.Tier tier,
            CompanyEnums.Priority priority,
            CompanyEnums.HiringNeed hiringNeed,
            SearchEnums.Timeline hiringTimeline,
            Integer anticipatedHires,
            CompanyEnums.LeadSource leadSource,
            LocalDate lastContactedDate,
            LocalDate followUpDate,
            String linkedinProfile,
            String notes,
            String owner
    ) {
    }

    public record ContactImportRequest(
            @NotNull(message = "Rows are required")
            @Size(max = MAX_ROWS, message = "Import at most 5000 rows at a time")
            List<@Valid ContactRow> rows,

            DuplicateMode duplicateMode
    ) {
    }

    public record ContactRow(
            String companyName,
            String companyWebsite,
            String companyLocation,
            String name,
            String firstName,
            String lastName,
            String title,
            String email,
            String phone,
            String linkedinUrl,
            OutreachStatus status,
            BuyingRole buyingRole,
            Integer touchCount,
            LocalDate lastContactedDate,
            LocalDate nextActionDate,
            ActionType nextActionType,
            String notes,
            String owner
    ) {
    }

    public record CandidateImportRequest(
            @NotNull(message = "Rows are required")
            @Size(max = MAX_ROWS, message = "Import at most 5000 rows at a time")
            List<@Valid CandidateRow> rows,

            DuplicateMode duplicateMode
    ) {
    }

    public record CandidateRow(
            String name,
            String firstName,
            String lastName,
            String email,
            String phone,
            String linkedinUrl,
            String location,
            String currentTitle,
            String currentCompany,
            SearchEnums.Track track,
            CandidateEnums.Status status,
            CandidateEnums.Source source,
            List<CandidateEnums.RoleArea> roleAreas,
            SearchEnums.WorkModel workPreference,
            CandidateEnums.Experience experience,
            Boolean willingToRelocate,
            CandidateEnums.EmploymentType employmentType,
            String compExpectation,
            String productionEvidence,
            LocalDate nextActionDate,
            ActionType nextActionType,
            String notes,
            String owner
    ) {
    }

    public record RowError(int row, String message) {
    }

    public record ImportResult(
            int created,
            int updated,
            int skipped,
            int companiesCreated,
            List<RowError> errors
    ) {
    }
}
