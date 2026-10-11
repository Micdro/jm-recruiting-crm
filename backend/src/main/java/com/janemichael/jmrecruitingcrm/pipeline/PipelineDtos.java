package com.janemichael.jmrecruitingcrm.pipeline;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

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
            PipelineStage stage,
            String rationale,
            Instant stageChangedAt,
            boolean placed
    ) {

        public static PipelineEntryResponse from(PipelineEntry entry, boolean placed) {
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
                    entry.getStage(),
                    entry.getRationale(),
                    entry.getStageChangedAt(),
                    placed
            );
        }
    }
}
