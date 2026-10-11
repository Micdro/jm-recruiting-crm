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
        DO_NOT_CONTACT
    }

    public enum Source {
        LINKEDIN,
        REFERRAL,
        INBOUND,
        NETWORK,
        OTHER
    }
}
