package com.janemichael.jmrecruitingcrm.company;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String website;
    private String location;
    private String companySize;
    private String status;
    private String linkedinProfile;
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