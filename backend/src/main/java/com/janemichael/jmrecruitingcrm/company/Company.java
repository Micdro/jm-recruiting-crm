package com.janemichael.jmrecruitingcrm.company;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Company name is required")
    @Size(max = 255, message = "Company name must be 255 characters or fewer")
    private String name;

    @Size(max = 500, message = "Website must be 500 characters or fewer")
    private String website;

    @Size(max = 255, message = "Location must be 255 characters or fewer")
    private String location;

    @Size(max = 100, message = "Company size must be 100 characters or fewer")
    private String companySize;

    @Size(max = 100, message = "Status must be 100 characters or fewer")
    private String status;

    @Size(max = 500, message = "LinkedIn profile must be 500 characters or fewer")
    private String linkedinProfile;

    @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
    private String notes;

    public Company() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getWebsite() {
        return website;
    }

    public String getLocation() {
        return location;
    }

    public String getCompanySize() {
        return companySize;
    }

    public String getStatus() {
        return status;
    }

    public String getLinkedinProfile() {
        return linkedinProfile;
    }

    public String getNotes() {
        return notes;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public void setCompanySize(String companySize) {
        this.companySize = companySize;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setLinkedinProfile(String linkedinProfile) {
        this.linkedinProfile = linkedinProfile;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}