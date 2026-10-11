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

    /** Matches the hiring timeline choices on the website intake form and in ClickUp. */
    public enum Timeline {
        /** Within 30 days. */
        ASAP,
        ONE_TO_TWO_MONTHS,
        THREE_PLUS_MONTHS,
        /** Building a pipeline for later. */
        EXPLORATORY
    }
}
