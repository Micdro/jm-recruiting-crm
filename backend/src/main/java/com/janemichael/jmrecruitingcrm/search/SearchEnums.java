package com.janemichael.jmrecruitingcrm.search;

/** Fixed choices for searches. */
public final class SearchEnums {

    private SearchEnums() {
    }

    /** The firm's two Specialist Search tracks. */
    public enum Track {
        PRODUCTION_AI_ENGINEERING,
        TECHNICAL_LEADERSHIP
    }

    public enum Status {
        /** Defining the search with the client. */
        INTAKE,
        ACTIVE,
        ON_HOLD,
        FILLED,
        CANCELLED;

        public boolean isOpen() {
            return this == INTAKE || this == ACTIVE || this == ON_HOLD;
        }
    }

    public enum EngagementType {
        CONTINGENCY,
        EXCLUSIVE,
        RETAINED
    }

    public enum WorkModel {
        REMOTE,
        HYBRID,
        ONSITE
    }

    /** Matches the timeline choices on the website's search intake form. */
    public enum Timeline {
        ASAP,
        WITHIN_60_DAYS,
        WITHIN_90_DAYS,
        EXPLORATORY
    }
}
