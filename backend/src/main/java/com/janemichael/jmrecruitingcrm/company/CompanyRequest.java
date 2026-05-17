package com.janemichael.jmrecruitingcrm.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CompanyRequest {

    @NotBlank(message = "Company is required")
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

    @Size(max = 500, message = "Linkedin profile must be 500 chracyers or fewer")
    private String linkedinProfile;

    @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
    private String notes;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCompanySize() {
        return companySize;
    }

    public void setCompanySize(String companySize) {
        this.companySize = companySize;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLinkedinProfile() {
        return linkedinProfile;
    }

    public void setLinkedinProfile(String linkedinProfile) {
        this.linkedinProfile = linkedinProfile;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
