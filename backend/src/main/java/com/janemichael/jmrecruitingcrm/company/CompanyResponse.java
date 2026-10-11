package com.janemichael.jmrecruitingcrm.company;

import java.time.Instant;

public record CompanyResponse(
        Long id,
        String name,
        String website,
        String location,
        String companySize,
        CompanyStatus status,
        String focus,
        String linkedinProfile,
        String notes,
        Long ownerId,
        String ownerName,
        long contactCount,
        long openSearchCount,
        Instant createdAt,
        Instant updatedAt
) {

    public static CompanyResponse from(Company company, long contactCount, long openSearchCount) {
        return new CompanyResponse(
                company.getId(),
                company.getName(),
                company.getWebsite(),
                company.getLocation(),
                company.getCompanySize(),
                company.getStatus(),
                company.getFocus(),
                company.getLinkedinProfile(),
                company.getNotes(),
                company.getOwner() == null ? null : company.getOwner().getId(),
                company.getOwner() == null ? null : company.getOwner().getDisplayName(),
                contactCount,
                openSearchCount,
                company.getCreatedAt(),
                company.getUpdatedAt()
        );
    }
}
