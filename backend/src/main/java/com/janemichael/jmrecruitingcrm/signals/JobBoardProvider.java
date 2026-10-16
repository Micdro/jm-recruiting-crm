package com.janemichael.jmrecruitingcrm.signals;

/** Where a company posts its open roles. */
public enum JobBoardProvider {
    GREENHOUSE,
    LEVER,
    ASHBY,
    /** Someone checked: the company has no board the CRM can read (or the one found was wrong). */
    NONE;

    /** A board the CRM can read. */
    public boolean isReadable() {
        return this != NONE;
    }
}
