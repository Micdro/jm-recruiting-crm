package com.janemichael.jmrecruitingcrm.pipeline;

import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.common.Auditable;
import com.janemichael.jmrecruitingcrm.common.EncryptedString;
import com.janemichael.jmrecruitingcrm.search.Search;
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

import java.time.Instant;

/** One candidate in one search. */
@Entity
@Table(name = "pipeline_entry")
public class PipelineEntry extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "search_id", nullable = false)
    private Search search;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineStage stage = PipelineStage.SOURCED;

    /** Why this candidate fits the search. Shown on the shortlist the client receives. */
    @Convert(converter = EncryptedString.class)
    private String rationale;

    @Column(name = "stage_changed_at", nullable = false)
    private Instant stageChangedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Search getSearch() {
        return search;
    }

    public void setSearch(Search search) {
        this.search = search;
    }

    public Candidate getCandidate() {
        return candidate;
    }

    public void setCandidate(Candidate candidate) {
        this.candidate = candidate;
    }

    public PipelineStage getStage() {
        return stage;
    }

    public void setStage(PipelineStage stage) {
        this.stage = stage;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public Instant getStageChangedAt() {
        return stageChangedAt;
    }

    public void setStageChangedAt(Instant stageChangedAt) {
        this.stageChangedAt = stageChangedAt;
    }
}
