package com.janemichael.jmrecruitingcrm.contact;

import java.time.LocalDate;

public class ContactResponse {

    private Long id;
    private String name;
    private String title;
    private String email;
    private String linkedinUrl;
    private String relationshipStatus;
    private LocalDate lastContactedDate;
    private LocalDate nextFollowUpDate;
    private String notes;
    private Long companyId;
    private String companyName;

    public ContactResponse(
            Long id,
            String name,
            String title,
            String email,
            String linkedinUrl,
            String relationshipStatus,
            LocalDate lastContactedDate,
            LocalDate nextFollowUpDate,
            String notes,
            Long companyId,
            String companyName
    ) {
        this.id = id;
        this.name = name;
        this.title = title;
        this.email = email;
        this.linkedinUrl = linkedinUrl;
        this.relationshipStatus = relationshipStatus;
        this.lastContactedDate = lastContactedDate;
        this.nextFollowUpDate = nextFollowUpDate;
        this.notes = notes;
        this.companyId = companyId;
        this.companyName = companyName;
    }

    public static ContactResponse fromEntity(Contact contact) {
        return new ContactResponse(
                contact.getId(),
                contact.getName(),
                contact.getTitle(),
                contact.getEmail(),
                contact.getLinkedinUrl(),
                contact.getRelationshipStatus(),
                contact.getLastContactedDate(),
                contact.getNextFollowUpDate(),
                contact.getNotes(),
                contact.getCompany().getId(),
                contact.getCompany().getName()
        );
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getEmail() {
        return email;
    }

    public String getLinkedinUrl() {
        return linkedinUrl;
    }

    public String getRelationshipStatus() {
        return relationshipStatus;
    }

    public LocalDate getLastContactedDate() {
        return lastContactedDate;
    }

    public LocalDate getNextFollowUpDate() {
        return nextFollowUpDate;
    }

    public String getNotes() {
        return notes;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public String getCompanyName() {
        return companyName;
    }
}