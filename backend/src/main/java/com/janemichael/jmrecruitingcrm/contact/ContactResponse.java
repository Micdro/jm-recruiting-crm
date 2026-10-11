package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.common.ActionType;

import java.time.Instant;
import java.time.LocalDate;

public record ContactResponse(
        Long id,
        String name,
        String title,
        String email,
        String phone,
        String linkedinUrl,
        OutreachStatus status,
        int touchCount,
        int maxTouches,
        LocalDate lastContactedDate,
        LocalDate nextActionDate,
        ActionType nextActionType,
        String notes,
        Long companyId,
        String companyName,
        Long ownerId,
        String ownerName,
        Instant createdAt,
        Instant updatedAt
) {

    public static ContactResponse from(Contact contact) {
        return new ContactResponse(
                contact.getId(),
                contact.getName(),
                contact.getTitle(),
                contact.getEmail(),
                contact.getPhone(),
                contact.getLinkedinUrl(),
                contact.getStatus(),
                contact.getTouchCount(),
                ContactService.MAX_SEQUENCE_TOUCHES,
                contact.getLastContactedDate(),
                contact.getNextActionDate(),
                contact.getNextActionType(),
                contact.getNotes(),
                contact.getCompany().getId(),
                contact.getCompany().getName(),
                contact.getOwner() == null ? null : contact.getOwner().getId(),
                contact.getOwner() == null ? null : contact.getOwner().getDisplayName(),
                contact.getCreatedAt(),
                contact.getUpdatedAt()
        );
    }
}
