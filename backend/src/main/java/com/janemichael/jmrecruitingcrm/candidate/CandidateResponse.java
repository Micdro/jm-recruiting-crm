package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.EnumCsv;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CandidateResponse(
        Long id,
        String name,
        String email,
        String phone,
        String linkedinUrl,
        String location,
        String currentTitle,
        String currentCompany,
        SearchEnums.Track track,
        CandidateEnums.Status status,
        CandidateEnums.Source source,
        boolean regulatedExperience,
        List<CandidateEnums.RoleArea> roleAreas,
        SearchEnums.WorkModel workPreference,
        CandidateEnums.Experience experience,
        boolean willingToRelocate,
        CandidateEnums.EmploymentType employmentType,
        String compExpectation,
        String resumeUrl,
        String productionEvidence,
        LocalDate lastContactedDate,
        LocalDate nextActionDate,
        ActionType nextActionType,
        String notes,
        Long ownerId,
        String ownerName,
        /** Searches the candidate is still active in. */
        long activeSearchCount,
        Instant createdAt,
        Instant updatedAt
) {

    public static CandidateResponse from(Candidate candidate, long activeSearchCount) {
        return build(candidate, candidate.getNotes(), activeSearchCount);
    }

    /** For lists and the Today queue: everything except the notes. */
    public static CandidateResponse summary(Candidate candidate, long activeSearchCount) {
        return build(candidate, null, activeSearchCount);
    }

    private static CandidateResponse build(Candidate candidate, String notes, long activeSearchCount) {
        return new CandidateResponse(
                candidate.getId(),
                candidate.getName(),
                candidate.getEmail(),
                candidate.getPhone(),
                candidate.getLinkedinUrl(),
                candidate.getLocation(),
                candidate.getCurrentTitle(),
                candidate.getCurrentCompany(),
                candidate.getTrack(),
                candidate.getStatus(),
                candidate.getSource(),
                candidate.isRegulatedExperience(),
                EnumCsv.split(candidate.getRoleAreas(), CandidateEnums.RoleArea.class),
                candidate.getWorkPreference(),
                candidate.getExperience(),
                candidate.isWillingToRelocate(),
                candidate.getEmploymentType(),
                candidate.getCompExpectation(),
                candidate.getResumeUrl(),
                candidate.getProductionEvidence(),
                candidate.getLastContactedDate(),
                candidate.getNextActionDate(),
                candidate.getNextActionType(),
                notes,
                candidate.getOwner() == null ? null : candidate.getOwner().getId(),
                candidate.getOwner() == null ? null : candidate.getOwner().getDisplayName(),
                activeSearchCount,
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }
}
