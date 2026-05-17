package com.janemichael.jmrecruitingcrm.company;

public class CompanyResponse {

    private Long id;
    private String name;
    private String website;
    private String location;
    private String companySize;
    private String status;
    private String linkedinProfile;
    private String notes;

    public CompanyResponse(
            Long id,
            String name,
            String website,
            String location,
            String companySize,
            String status,
            String linkedinProfile,
            String notes
    ) {
        this.id = id;
        this.name = name;
        this.website = website;
        this.location = location;
        this.companySize = companySize;
        this.status = status;
        this.linkedinProfile = linkedinProfile;
        this.notes = notes;
    }

    public static CompanyResponse fromEntity(Company company) {
        return new CompanyResponse(
                company.getId(),
                company.getName(),
                company.getWebsite(),
                company.getLocation(),
                company.getCompanySize(),
                company.getStatus(),
                company.getLinkedinProfile(),
                company.getNotes()
        );
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
}
