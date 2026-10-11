package com.janemichael.jmrecruitingcrm.pipeline;

/**
 * A candidate's stage in one search. Follows the firm's process:
 * map the market, assess the evidence, build a focused shortlist, support the process.
 */
public enum PipelineStage {
    SOURCED,
    CONTACTED,
    /** Checking evidence of production ownership, decision authority and failure handling. */
    ASSESSING,
    /** On the focused shortlist, with a rationale. */
    SHORTLISTED,
    /** Presented to the client. */
    SUBMITTED,
    INTERVIEWING,
    OFFER,
    PLACED,
    CLIENT_PASSED,
    WITHDREW;

    public boolean isActive() {
        return this != PLACED && this != CLIENT_PASSED && this != WITHDREW;
    }
}
