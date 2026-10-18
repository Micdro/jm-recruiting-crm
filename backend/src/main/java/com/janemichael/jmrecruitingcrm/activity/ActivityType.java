package com.janemichael.jmrecruitingcrm.activity;

import com.janemichael.jmrecruitingcrm.common.ActionType;

public enum ActivityType {
    EMAIL,
    LINKEDIN,
    CALL,
    MEETING,
    /** The person wrote back. */
    REPLY,
    NOTE,
    /** Recorded automatically when a status changes. */
    STATUS_CHANGE,
    /** Recorded automatically when a candidate moves stages in a search. */
    STAGE_CHANGE;

    /** Outbound outreach. These count toward the daily touch target. */
    public boolean isOutreach() {
        return this == EMAIL || this == LINKEDIN || this == CALL;
    }

    /** Types a person can log by hand. The others are recorded by the system. */
    public boolean isManual() {
        return this != STATUS_CHANGE && this != STAGE_CHANGE;
    }

    /** How logging it reads in Recent changes: "Logged a call with Ana Ruiz". */
    public String logged(String name) {
        return switch (this) {
            case EMAIL -> "Logged an email to " + name;
            case LINKEDIN -> "Logged a LinkedIn message to " + name;
            case CALL -> "Logged a call with " + name;
            case MEETING -> "Logged a meeting with " + name;
            case REPLY -> "Logged a reply from " + name;
            case NOTE -> "Added a note on " + name;
            default -> "Updated " + name;
        };
    }

    /** The matching next-action type, used when scheduling the next touch. */
    public ActionType toActionType() {
        return switch (this) {
            case EMAIL -> ActionType.EMAIL;
            // Replies come in on LinkedIn, so the answer goes there too.
            case LINKEDIN, REPLY -> ActionType.LINKEDIN;
            case CALL -> ActionType.CALL;
            case MEETING -> ActionType.MEETING;
            default -> ActionType.OTHER;
        };
    }
}
