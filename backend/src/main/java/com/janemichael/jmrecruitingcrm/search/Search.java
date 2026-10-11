package com.janemichael.jmrecruitingcrm.search;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.common.Auditable;
import com.janemichael.jmrecruitingcrm.common.EncryptedString;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.contact.Contact;
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
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A client engagement to fill one role: the brief, terms and status. */
@Entity(name = "JobSearch")
@Table(name = "job_search")
public class Search extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hiring_manager_id")
    private Contact hiringManager;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SearchEnums.Track track;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SearchEnums.Status status = SearchEnums.Status.INTAKE;

    @Enumerated(EnumType.STRING)
    @Column(name = "engagement_type", nullable = false)
    private SearchEnums.EngagementType engagementType = SearchEnums.EngagementType.CONTINGENCY;

    @Column(name = "fee_percent")
    private BigDecimal feePercent;

    @Column(name = "base_min")
    private Integer baseMin;

    @Column(name = "base_max")
    private Integer baseMax;

    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_model")
    private SearchEnums.WorkModel workModel;

    @Enumerated(EnumType.STRING)
    private SearchEnums.Timeline timeline;

    @Column(name = "opened_date")
    private LocalDate openedDate;

    /** The production outcome this hire must deliver. */
    @Convert(converter = EncryptedString.class)
    @Column(name = "production_outcome")
    private String productionOutcome;

    @Convert(converter = EncryptedString.class)
    @Column(name = "technical_scope")
    private String technicalScope;

    /** What the person will own and answer for. */
    @Convert(converter = EncryptedString.class)
    private String accountability;

    @Convert(converter = EncryptedString.class)
    @Column(name = "regulatory_constraints")
    private String regulatoryConstraints;

    @Convert(converter = EncryptedString.class)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public Contact getHiringManager() {
        return hiringManager;
    }

    public void setHiringManager(Contact hiringManager) {
        this.hiringManager = hiringManager;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public SearchEnums.Track getTrack() {
        return track;
    }

    public void setTrack(SearchEnums.Track track) {
        this.track = track;
    }

    public SearchEnums.Status getStatus() {
        return status;
    }

    public void setStatus(SearchEnums.Status status) {
        this.status = status;
    }

    public SearchEnums.EngagementType getEngagementType() {
        return engagementType;
    }

    public void setEngagementType(SearchEnums.EngagementType engagementType) {
        this.engagementType = engagementType;
    }

    public BigDecimal getFeePercent() {
        return feePercent;
    }

    public void setFeePercent(BigDecimal feePercent) {
        this.feePercent = feePercent;
    }

    public Integer getBaseMin() {
        return baseMin;
    }

    public void setBaseMin(Integer baseMin) {
        this.baseMin = baseMin;
    }

    public Integer getBaseMax() {
        return baseMax;
    }

    public void setBaseMax(Integer baseMax) {
        this.baseMax = baseMax;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public SearchEnums.WorkModel getWorkModel() {
        return workModel;
    }

    public void setWorkModel(SearchEnums.WorkModel workModel) {
        this.workModel = workModel;
    }

    public SearchEnums.Timeline getTimeline() {
        return timeline;
    }

    public void setTimeline(SearchEnums.Timeline timeline) {
        this.timeline = timeline;
    }

    public LocalDate getOpenedDate() {
        return openedDate;
    }

    public void setOpenedDate(LocalDate openedDate) {
        this.openedDate = openedDate;
    }

    public String getProductionOutcome() {
        return productionOutcome;
    }

    public void setProductionOutcome(String productionOutcome) {
        this.productionOutcome = productionOutcome;
    }

    public String getTechnicalScope() {
        return technicalScope;
    }

    public void setTechnicalScope(String technicalScope) {
        this.technicalScope = technicalScope;
    }

    public String getAccountability() {
        return accountability;
    }

    public void setAccountability(String accountability) {
        this.accountability = accountability;
    }

    public String getRegulatoryConstraints() {
        return regulatoryConstraints;
    }

    public void setRegulatoryConstraints(String regulatoryConstraints) {
        this.regulatoryConstraints = regulatoryConstraints;
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
