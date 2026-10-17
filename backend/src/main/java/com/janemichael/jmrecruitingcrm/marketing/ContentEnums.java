package com.janemichael.jmrecruitingcrm.marketing;

/** The choices on a post. */
public final class ContentEnums {

    private ContentEnums() {
    }

    /** Where a post is in its life. An idea has no date; a planned post does. */
    public enum PostStatus {
        IDEA,
        DRAFT,
        READY,
        POSTED
    }

    /** Whose LinkedIn it goes on: the owner's own profile, or the firm's company page. */
    public enum PostAccount {
        PERSONAL,
        COMPANY_PAGE
    }

    /** What a post is about, so the week stays varied and results can be compared by topic. */
    public enum PostTheme {
        FDE_HIRING,
        AI_PLATFORM_HIRING,
        BFSI_AI_SECURITY,
        FIRM_NEWS,
        OTHER
    }
}
