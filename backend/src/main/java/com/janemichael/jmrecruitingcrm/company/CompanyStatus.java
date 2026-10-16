package com.janemichael.jmrecruitingcrm.company;

/** The client pipeline, the same stages as the Client List in ClickUp. */
public enum CompanyStatus {
    /**
     * Found by the prospect finder and not reviewed yet. Kept apart from the pipeline: its contacts
     * stay out of the outreach queue until someone accepts it (it becomes a lead) or dismisses it.
     */
    AI_FOUND,
    LEAD,
    INITIAL_OUTREACH,
    ENGAGED,
    DISCOVERY_SCHEDULED,
    DISCOVERY_COMPLETED,
    CONTRACT_SENT,
    CONTRACT_SIGNED,
    ONBOARDED,
    /** The firm is actively placing for them. */
    PLACING,
    LONG_TERM_INTEREST,
    DORMANT,
    NOT_QUALIFIED,
    NOT_INTERESTED,
    COMPLETE;

    /** A signed client the firm is working for. */
    public boolean isActiveClient() {
        return this == CONTRACT_SIGNED || this == ONBOARDED || this == PLACING;
    }
}
