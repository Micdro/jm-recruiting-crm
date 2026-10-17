package com.janemichael.jmrecruitingcrm.prospects;

/** Why the team turned down a company the finder suggested. The finder reads these to do better. */
public enum DismissReason {
    NOT_FINANCIAL_SERVICES("doesn't sell to banks, insurers or financial services"),
    NOT_A_VENDOR("not a software vendor (a bank, insurer, consultancy or staffing firm)"),
    NOT_AI_OR_SECURITY("doesn't sell an AI or security product"),
    TOO_BIG("too big to need outside recruiters"),
    TOO_SMALL("too small or too early to hire"),
    NO_HIRING_NEED("no sign they're hiring these engineers"),
    OTHER("another reason");

    /** How the finder's instructions put it. */
    public final String meaning;

    DismissReason(String meaning) {
        this.meaning = meaning;
    }
}
