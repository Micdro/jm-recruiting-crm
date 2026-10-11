package com.janemichael.jmrecruitingcrm.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record SearchResponse(
        Long id,
        Long companyId,
        String companyName,
        Long hiringManagerId,
        String hiringManagerName,
        String title,
        SearchEnums.Track track,
        SearchEnums.Status status,
        SearchEnums.EngagementType engagementType,
        BigDecimal feePercent,
        Integer baseMin,
        Integer baseMax,
        String location,
        SearchEnums.WorkModel workModel,
        SearchEnums.Timeline timeline,
        LocalDate openedDate,
        String productionOutcome,
        String technicalScope,
        String accountability,
        String regulatoryConstraints,
        String notes,
        Long ownerId,
        String ownerName,
        /** Candidates per pipeline stage, keyed by stage name. */
        Map<String, Long> stageCounts,
        Instant createdAt,
        Instant updatedAt
) {

    public static SearchResponse from(Search search, Map<String, Long> stageCounts) {
        return new SearchResponse(
                search.getId(),
                search.getCompany().getId(),
                search.getCompany().getName(),
                search.getHiringManager() == null ? null : search.getHiringManager().getId(),
                search.getHiringManager() == null ? null : search.getHiringManager().getName(),
                search.getTitle(),
                search.getTrack(),
                search.getStatus(),
                search.getEngagementType(),
                search.getFeePercent(),
                search.getBaseMin(),
                search.getBaseMax(),
                search.getLocation(),
                search.getWorkModel(),
                search.getTimeline(),
                search.getOpenedDate(),
                search.getProductionOutcome(),
                search.getTechnicalScope(),
                search.getAccountability(),
                search.getRegulatoryConstraints(),
                search.getNotes(),
                search.getOwner() == null ? null : search.getOwner().getId(),
                search.getOwner() == null ? null : search.getOwner().getDisplayName(),
                stageCounts == null ? Map.of() : stageCounts,
                search.getCreatedAt(),
                search.getUpdatedAt()
        );
    }
}
