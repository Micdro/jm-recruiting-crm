package com.janemichael.jmrecruitingcrm.activity;

import java.time.Instant;

public record ActivityResponse(
        Long id,
        ActivityType type,
        boolean touch,
        String summary,
        Instant occurredAt,
        Long userId,
        String userName,
        Long companyId,
        String companyName,
        Long contactId,
        String contactName,
        Long candidateId,
        String candidateName,
        Long searchId,
        String searchTitle
) {

    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(
                activity.getId(),
                activity.getType(),
                activity.isTouch(),
                activity.getSummary(),
                activity.getOccurredAt(),
                activity.getUser() == null ? null : activity.getUser().getId(),
                activity.getUser() == null ? null : activity.getUser().getDisplayName(),
                activity.getCompany() == null ? null : activity.getCompany().getId(),
                activity.getCompany() == null ? null : activity.getCompany().getName(),
                activity.getContact() == null ? null : activity.getContact().getId(),
                activity.getContact() == null ? null : activity.getContact().getName(),
                activity.getCandidate() == null ? null : activity.getCandidate().getId(),
                activity.getCandidate() == null ? null : activity.getCandidate().getName(),
                activity.getSearch() == null ? null : activity.getSearch().getId(),
                activity.getSearch() == null ? null : activity.getSearch().getTitle()
        );
    }
}
