package com.janemichael.jmrecruitingcrm.company;

import java.time.LocalDate;

/**
 * Whether a company's people are off-limits as candidates, and why. The firm doesn't recruit from
 * a client while it works for them (a signed client, or a search that's still open), nor for a
 * year after the last placement there starts. A date set on the company replaces that year.
 *
 * @param client true while the firm works for them
 * @param until  the day the protection ends, when that's still ahead (null while it lasts only as
 *               long as the work does)
 */
public record OffLimits(boolean client, LocalDate until) {

    public static final int MONTHS_AFTER_PLACEMENT = 12;

    /** Null when the company's people may be approached. */
    public static OffLimits of(Company company, long openSearches, LocalDate lastPlacementStart, LocalDate today) {
        boolean client = company.getStatus().isActiveClient() || openSearches > 0;
        LocalDate until = company.getOffLimitsUntil() != null
                ? company.getOffLimitsUntil()
                : lastPlacementStart == null ? null : lastPlacementStart.plusMonths(MONTHS_AFTER_PLACEMENT);
        if (until != null && !until.isAfter(today)) {
            until = null;
        }
        return client || until != null ? new OffLimits(client, until) : null;
    }
}
