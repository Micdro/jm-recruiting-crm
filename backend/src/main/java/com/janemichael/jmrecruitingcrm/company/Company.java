package com.janemichael.jmrecruitingcrm.company;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
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

/** A client or prospect: an AI-native vendor selling into regulated financial institutions. */
@Entity
public class Company extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String name;

    /** A keyed fingerprint of the name, so duplicate names are caught without decrypting. */
    @Column(name = "name_hash")
    private String nameHash;

    @Convert(converter = EncryptedString.class)
    private String website;

    @Convert(converter = EncryptedString.class)
    private String location;

    @Column(name = "company_size")
    private String companySize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus status = CompanyStatus.LEAD;

    /** What the company sells and to whom, for example "AI fraud detection for banks". */
    @Convert(converter = EncryptedString.class)
    private String focus;

    @Enumerated(EnumType.STRING)
    private CompanyEnums.Industry industry;

    /** Comma-separated {@link CompanyEnums.Segment} names. */
    private String segments;

    @Enumerated(EnumType.STRING)
    private CompanyEnums.Tier tier;

    @Enumerated(EnumType.STRING)
    private CompanyEnums.Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "hiring_need")
    private CompanyEnums.HiringNeed hiringNeed;

    @Enumerated(EnumType.STRING)
    @Column(name = "hiring_timeline")
    private SearchEnums.Timeline hiringTimeline;

    @Column(name = "anticipated_hires")
    private Integer anticipatedHires;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_source")
    private CompanyEnums.LeadSource leadSource;

    @Column(name = "last_contacted_date")
    private LocalDate lastContactedDate;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    /** Set by hand: replaces the usual year of off-limits after the last placement. */
    @Column(name = "off_limits_until")
    private LocalDate offLimitsUntil;

    @Column(name = "linkedin_profile")
    @Convert(converter = EncryptedString.class)
    private String linkedinProfile;

    @Convert(converter = EncryptedString.class)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    public Company() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @PrePersist
    @PreUpdate
    void indexName() {
        nameHash = FieldEncryptor.current().blindIndex(name);
    }

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

    public CompanyStatus getStatus() {
        return status;
    }

    public void setStatus(CompanyStatus status) {
        this.status = status;
    }

    public String getFocus() {
        return focus;
    }

    public void setFocus(String focus) {
        this.focus = focus;
    }

    public CompanyEnums.Industry getIndustry() {
        return industry;
    }

    public void setIndustry(CompanyEnums.Industry industry) {
        this.industry = industry;
    }

    public String getSegments() {
        return segments;
    }

    public void setSegments(String segments) {
        this.segments = segments;
    }

    public CompanyEnums.Tier getTier() {
        return tier;
    }

    public void setTier(CompanyEnums.Tier tier) {
        this.tier = tier;
    }

    public CompanyEnums.Priority getPriority() {
        return priority;
    }

    public void setPriority(CompanyEnums.Priority priority) {
        this.priority = priority;
    }

    public CompanyEnums.HiringNeed getHiringNeed() {
        return hiringNeed;
    }

    public void setHiringNeed(CompanyEnums.HiringNeed hiringNeed) {
        this.hiringNeed = hiringNeed;
    }

    public SearchEnums.Timeline getHiringTimeline() {
        return hiringTimeline;
    }

    public void setHiringTimeline(SearchEnums.Timeline hiringTimeline) {
        this.hiringTimeline = hiringTimeline;
    }

    public Integer getAnticipatedHires() {
        return anticipatedHires;
    }

    public void setAnticipatedHires(Integer anticipatedHires) {
        this.anticipatedHires = anticipatedHires;
    }

    public CompanyEnums.LeadSource getLeadSource() {
        return leadSource;
    }

    public void setLeadSource(CompanyEnums.LeadSource leadSource) {
        this.leadSource = leadSource;
    }

    public LocalDate getLastContactedDate() {
        return lastContactedDate;
    }

    public void setLastContactedDate(LocalDate lastContactedDate) {
        this.lastContactedDate = lastContactedDate;
    }

    public LocalDate getFollowUpDate() {
        return followUpDate;
    }

    public void setFollowUpDate(LocalDate followUpDate) {
        this.followUpDate = followUpDate;
    }

    public LocalDate getOffLimitsUntil() {
        return offLimitsUntil;
    }

    public void setOffLimitsUntil(LocalDate offLimitsUntil) {
        this.offLimitsUntil = offLimitsUntil;
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

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }
}
