package com.janemichael.jmrecruitingcrm.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class ContactRequest {

    @NotBlank(message = "Contact name is required")
    @Size(max = 255, message = "Contact name must be 255 characters or fewer")
    private String name;

    @Size(max = 255, message = "Title must be 255 characters or fewer")
    private String title;

    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must be 255 characters or fewer")
    private String email;

    @Size(max = 500, message = "LinkedIn URL must be 500 characters or fewer")
    private String linkedinUrl;

    @Size(max = 100, message = "Relationship status must be 100 characters or fewer")
    private String relationshipStatus;

    private LocalDate lastContactedDate;

    private LocalDate nextFollowUpDate;

    @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
    private String notes;

    @NotNull(message = "Company ID is required")
    private Long companyId;

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

    public void setName(String name) {
        this.name = name;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setLinkedinUrl(String linkedinUrl) {
        this.linkedinUrl = linkedinUrl;
    }

    public void setRelationshipStatus(String relationshipStatus) {
        this.relationshipStatus = relationshipStatus;
    }

    public void setLastContactedDate(LocalDate lastContactedDate) {
        this.lastContactedDate = lastContactedDate;
    }

    public void setNextFollowUpDate(LocalDate nextFollowUpDate) {
        this.nextFollowUpDate = nextFollowUpDate;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }
}