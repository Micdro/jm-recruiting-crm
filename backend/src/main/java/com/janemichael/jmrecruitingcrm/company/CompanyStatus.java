package com.janemichael.jmrecruitingcrm.company;

/** Where a client account stands in business development. */
public enum CompanyStatus {
    /** On the target list; no outreach yet. */
    TARGET,
    /** Outreach to its people is under way. */
    PROSPECTING,
    /** Real conversations about hiring are happening. */
    IN_CONVERSATION,
    /** Has signed an engagement with the firm. */
    CLIENT,
    /** Outside the niche or ruled out. */
    NOT_A_FIT
}
