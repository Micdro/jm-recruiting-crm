package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.Auditable;
import com.janemichael.jmrecruitingcrm.common.EncryptedString;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.LocalDate;

/** A production AI engineer or technical leader the firm represents. */
@Entity
public class Candidate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Convert(converter = EncryptedString.class)
    private String email;

    @Column(name = "email_hash")
    private String emailHash;

    @Convert(converter = EncryptedString.class)
    private String phone;

    @Column(name = "linkedin_url")
    private String linkedinUrl;

    private String location;

    @Column(name = "current_title")
    private String currentTitle;

    @Column(name = "current_company")
    private String currentCompany;

    @Enumerated(EnumType.STRING)
    private SearchEnums.Track track;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CandidateEnums.Status status = CandidateEnums.Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    private CandidateEnums.Source source;

    /** Has built or run production systems for banks, insurers or other regulated institutions. */
    @Column(name = "regulated_experience", nullable = false)
    private boolean regulatedExperience;

    /** Comma-separated {@link CandidateEnums.RoleArea} names. */
    @Column(name = "role_areas")
    private String roleAreas;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_preference")
    private SearchEnums.WorkModel workPreference;

    @Enumerated(EnumType.STRING)
    private CandidateEnums.Experience experience;

    @Column(name = "willing_to_relocate", nullable = false)
    private boolean willingToRelocate;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type")
    private CandidateEnums.EmploymentType employmentType;

    @Convert(converter = EncryptedString.class)
    @Column(name = "comp_expectation")
    private String compExpectation;

    @Convert(converter = EncryptedString.class)
    @Column(name = "resume_url")
    private String resumeUrl;

    /** What they have owned in production: systems, decisions, incidents. Accountability over titles. */
    @Convert(converter = EncryptedString.class)
    @Column(name = "production_evidence")
    private String productionEvidence;

    @Column(name = "last_contacted_date")
    private LocalDate lastContactedDate;

    @Column(name = "next_action_date")
    private LocalDate nextActionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_action_type")
    private ActionType nextActionType;

    @Convert(converter = EncryptedString.class)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    @PrePersist
    @PreUpdate
    void indexEmail() {
        emailHash = FieldEncryptor.current().blindIndex(email);
    }

    public String getEmailHash() {
        return emailHash;
    }

    public String getRoleAreas() {
        return roleAreas;
    }

    public void setRoleAreas(String roleAreas) {
        this.roleAreas = roleAreas;
    }

    public SearchEnums.WorkModel getWorkPreference() {
        return workPreference;
    }

    public void setWorkPreference(SearchEnums.WorkModel workPreference) {
        this.workPreference = workPreference;
    }

    public CandidateEnums.Experience getExperience() {
        return experience;
    }

    public void setExperience(CandidateEnums.Experience experience) {
        this.experience = experience;
    }

    public boolean isWillingToRelocate() {
        return willingToRelocate;
    }

    public void setWillingToRelocate(boolean willingToRelocate) {
        this.willingToRelocate = willingToRelocate;
    }

    public CandidateEnums.EmploymentType getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(CandidateEnums.EmploymentType employmentType) {
        this.employmentType = employmentType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getLinkedinUrl() {
        return linkedinUrl;
    }

    public void setLinkedinUrl(String linkedinUrl) {
        this.linkedinUrl = linkedinUrl;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public void setCurrentTitle(String currentTitle) {
        this.currentTitle = currentTitle;
    }

    public String getCurrentCompany() {
        return currentCompany;
    }

    public void setCurrentCompany(String currentCompany) {
        this.currentCompany = currentCompany;
    }

    public SearchEnums.Track getTrack() {
        return track;
    }

    public void setTrack(SearchEnums.Track track) {
        this.track = track;
    }

    public CandidateEnums.Status getStatus() {
        return status;
    }

    public void setStatus(CandidateEnums.Status status) {
        this.status = status;
    }

    public CandidateEnums.Source getSource() {
        return source;
    }

    public void setSource(CandidateEnums.Source source) {
        this.source = source;
    }

    public boolean isRegulatedExperience() {
        return regulatedExperience;
    }

    public void setRegulatedExperience(boolean regulatedExperience) {
        this.regulatedExperience = regulatedExperience;
    }

    public String getCompExpectation() {
        return compExpectation;
    }

    public void setCompExpectation(String compExpectation) {
        this.compExpectation = compExpectation;
    }

    public String getResumeUrl() {
        return resumeUrl;
    }

    public void setResumeUrl(String resumeUrl) {
        this.resumeUrl = resumeUrl;
    }

    public String getProductionEvidence() {
        return productionEvidence;
    }

    public void setProductionEvidence(String productionEvidence) {
        this.productionEvidence = productionEvidence;
    }

    public LocalDate getLastContactedDate() {
        return lastContactedDate;
    }

    public void setLastContactedDate(LocalDate lastContactedDate) {
        this.lastContactedDate = lastContactedDate;
    }

    public LocalDate getNextActionDate() {
        return nextActionDate;
    }

    public void setNextActionDate(LocalDate nextActionDate) {
        this.nextActionDate = nextActionDate;
    }

    public ActionType getNextActionType() {
        return nextActionType;
    }

    public void setNextActionType(ActionType nextActionType) {
        this.nextActionType = nextActionType;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }
}
