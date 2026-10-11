package com.janemichael.jmrecruitingcrm.company;

/** The account fields from the ClickUp Client List. */
public final class CompanyEnums {

    private CompanyEnums() {
    }

    /** Which regulated institutions the vendor sells to. */
    public enum Industry {
        BANKS,
        FINANCIAL_SERVICES,
        INSURANCE,
        UNCLEAR
    }

    /** What the vendor's product does (ClickUp "Subtype"). A company can have several. */
    public enum Segment {
        FRAUD_IDENTITY_RISK,
        DECISIONING,
        COMPLIANCE_AML,
        WORKFLOWS_PLATFORMS
    }

    /** How hard to pursue the account (ClickUp "Relations"). */
    public enum Tier {
        PURSUE_NOW,
        BUILD_EARLY,
        CLOSELY_MONITOR,
        MONITOR,
        HOLD
    }

    public enum Priority {
        URGENT,
        HIGH,
        NORMAL,
        LOW
    }

    /** The kind of search they need (ClickUp "Hiring Role"). */
    public enum HiringNeed {
        FDE,
        AI_PLATFORM,
        BOTH,
        NOT_SURE
    }

    public enum LeadSource {
        AI_RESEARCH,
        LINKEDIN,
        COLD_CALL,
        WEBSITE,
        INBOUND_CALL,
        SOCIAL,
        REFERRAL,
        EVENT,
        OTHER
    }
}
