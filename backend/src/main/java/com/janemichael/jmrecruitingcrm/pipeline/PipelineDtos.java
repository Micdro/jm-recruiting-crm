package com.janemichael.jmrecruitingcrm.pipeline;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class PipelineDtos {

    private PipelineDtos() {
    }

    public record AddToSearchRequest(
            @NotNull(message = "Search is required")
            Long searchId,

            @NotNull(message = "Candidate is required")
            Long candidateId,

            PipelineStage stage,

            @Size(max = 2000, message = "Rationale must be 2000 characters or fewer")
            String rationale
    ) {
    }

    public record UpdateEntryRequest(
            @NotNull(message = "Stage is required")
            PipelineStage stage,

            @Size(max = 2000, message = "Rationale must be 2000 characters or fewer")
            String rationale
    ) {
    }

    /** Schedules (or moves) the next interview. A date in the past records one that already happened. */
    public record InterviewRequest(
            @NotNull(message = "Choose the interview date")
            LocalDate date,

            LocalTime time,

            @Size(max = 200, message = "Keep the interview name to 200 characters or fewer")
            String label
    ) {
    }

    public enum DebriefWith { CANDIDATE, CLIENT }

    public record DebriefRequest(
            @NotNull(message = "Choose who you debriefed")
            DebriefWith with,

            @Size(max = 4000, message = "Debrief notes must be 4000 characters or fewer")
            String notes
    ) {
    }

    public record PipelineEntryResponse(
            Long id,
            Long searchId,
            String searchTitle,
            Long companyId,
            String companyName,
            Long candidateId,
            String candidateName,
            String candidateTitle,
            String candidateCompany,
            String candidateLinkedinUrl,
            String candidateLocation,
            String candidateEvidence,
            PipelineStage stage,
            String rationale,
            Instant stageChangedAt,
            boolean placed,
            LocalDate interviewDate,
            LocalTime interviewTime,
            String interviewLabel,
            boolean candidateDebriefed,
            boolean clientDebriefed,
            /** The interview is over and a debrief is still to do. False when no time was given to judge by. */
            boolean debriefDue,
            /** Who runs the search (or owns the candidate, when the search has no owner), for Today. */
            Long ownerId,
            String ownerName
    ) {

        public static PipelineEntryResponse from(PipelineEntry entry, boolean placed) {
            return from(entry, placed, null);
        }

        /** With {@code now}, debriefDue says whether the interview is over and a debrief is still to do. */
        public static PipelineEntryResponse from(PipelineEntry entry, boolean placed, LocalDateTime now) {
            return new PipelineEntryResponse(
                    entry.getId(),
                    entry.getSearch().getId(),
                    entry.getSearch().getTitle(),
                    entry.getSearch().getCompany().getId(),
                    entry.getSearch().getCompany().getName(),
                    entry.getCandidate().getId(),
                    entry.getCandidate().getName(),
                    entry.getCandidate().getCurrentTitle(),
                    entry.getCandidate().getCurrentCompany(),
                    entry.getCandidate().getLinkedinUrl(),
                    entry.getCandidate().getLocation(),
                    entry.getCandidate().getProductionEvidence(),
                    entry.getStage(),
                    entry.getRationale(),
                    entry.getStageChangedAt(),
                    placed,
                    entry.getInterviewDate(),
                    entry.getInterviewTime(),
                    entry.getInterviewLabel(),
                    entry.isCandidateDebriefed(),
                    entry.isClientDebriefed(),
                    now != null && Interviews.debriefDue(entry, now),
                    ownerOf(entry) == null ? null : ownerOf(entry).getId(),
                    ownerOf(entry) == null ? null : ownerOf(entry).getDisplayName()
            );
        }

        static AppUser ownerOf(PipelineEntry entry) {
            return entry.getSearch().getOwner() != null ? entry.getSearch().getOwner() : entry.getCandidate().getOwner();
        }
    }
}
