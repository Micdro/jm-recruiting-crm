package com.janemichael.jmrecruitingcrm.candidate;

public final class CandidateEnums {

    private CandidateEnums() {
    }

    public enum Status {
        /** Open to moves now. */
        ACTIVE,
        /** Employed and open to the right conversation. */
        PASSIVE,
        PLACED,
        NOT_LOOKING,
        /** Assessed and not a fit for the firm's searches (ClickUp "dq"). */
        NOT_A_FIT,
        DO_NOT_CONTACT
    }

    public enum Source {
        LINKEDIN,
        REFERRAL,
        /** Applied or reached out, for example through the website. */
        INBOUND,
        NETWORK,
        INDEED,
        ZIPRECRUITER,
        OTHER
    }

    /** Which part of production AI they do (ClickUp "Applicable Role(s)"): design, deploy, operate. */
    public enum RoleArea {
        DESIGN,
        DEPLOY,
        OPERATE,
        OTHER
    }

    public enum Experience {
        YEARS_1_2,
        YEARS_3_4,
        YEARS_5_7,
        YEARS_8_9,
        YEARS_10_PLUS
    }

    public enum EmploymentType {
        FULL_TIME,
        CONTRACT,
        EITHER
    }
}
