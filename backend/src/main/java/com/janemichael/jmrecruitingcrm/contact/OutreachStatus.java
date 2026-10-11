package com.janemichael.jmrecruitingcrm.contact;

/** Where a client-side contact stands in outbound outreach. */
public enum OutreachStatus {
    /** Not touched yet. */
    NEW,
    /** Between touch 1 and touch 4 of the cold sequence. */
    IN_SEQUENCE,
    REPLIED,
    MEETING_BOOKED,
    /** An active working relationship, for example a hiring manager on a search. */
    ENGAGED,
    /** Not now; revisit later. */
    NURTURE,
    /** All four touches sent without a reply. */
    NO_RESPONSE,
    NOT_INTERESTED,
    DO_NOT_CONTACT;

    /** Statuses where the 4-touch cold sequence applies. */
    public boolean isCold() {
        return this == NEW || this == IN_SEQUENCE;
    }

    /** Statuses that should never appear in the outreach queue. */
    public boolean isClosed() {
        return this == NOT_INTERESTED || this == DO_NOT_CONTACT;
    }
}
