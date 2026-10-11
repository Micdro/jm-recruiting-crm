package com.janemichael.jmrecruitingcrm.company;

import com.janemichael.jmrecruitingcrm.common.EnumCsv;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CompanyResponse(
        Long id,
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
        Long ownerId,
        String ownerName,
        long contactCount,
        long openSearchCount,
        Instant createdAt,
        Instant updatedAt
) {

    public static CompanyResponse from(Company company, long contactCount, long openSearchCount) {
        return build(company, company.getNotes(), contactCount, openSearchCount);
    }

    /** For lists: everything except the notes, which can be long research memos. */
    public static CompanyResponse summary(Company company, long contactCount, long openSearchCount) {
        return build(company, null, contactCount, openSearchCount);
    }

    private static CompanyResponse build(Company company, String notes, long contactCount, long openSearchCount) {
        return new CompanyResponse(
                company.getId(),
                company.getName(),
                company.getWebsite(),
                company.getLocation(),
                company.getCompanySize(),
                company.getStatus(),
                company.getFocus(),
                company.getIndustry(),
                EnumCsv.split(company.getSegments(), CompanyEnums.Segment.class),
                company.getTier(),
                company.getPriority(),
                company.getHiringNeed(),
                company.getHiringTimeline(),
                company.getAnticipatedHires(),
                company.getLeadSource(),
                company.getLastContactedDate(),
                company.getFollowUpDate(),
                company.getLinkedinProfile(),
                notes,
                company.getOwner() == null ? null : company.getOwner().getId(),
                company.getOwner() == null ? null : company.getOwner().getDisplayName(),
                contactCount,
                openSearchCount,
                company.getCreatedAt(),
                company.getUpdatedAt()
        );
    }
}
