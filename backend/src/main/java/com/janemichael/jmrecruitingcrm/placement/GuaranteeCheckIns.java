package com.janemichael.jmrecruitingcrm.placement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Check-ins with a placed candidate and the client while the guarantee runs: 30 and 60 days after
 * the start date. Catching a problem early is how a fee is kept, so each check-in shows on Today
 * from its day until someone checks in. One check-in covers every point that has come, so a missed
 * 30-day check-in doesn't leave two to do. Points after the guarantee ends are left out, and
 * nothing is due once it has ended.
 */
public final class GuaranteeCheckIns {

    public static final List<Integer> DAYS = List.of(30, 60);

    /** The check-in due now, or the next one coming: days after the start, its date, and whether it's due. */
    public record CheckIn(int days, LocalDate date, boolean due) {
    }

    private GuaranteeCheckIns() {
    }

    public static Optional<CheckIn> next(Placement placement, LocalDate today) {
        LocalDate start = placement.getStartDate();
        LocalDate end = placement.getGuaranteeEndDate();
        if (start == null || today == null || (end != null && today.isAfter(end))) {
            return Optional.empty();
        }

        CheckIn due = null;
        for (int days : DAYS) {
            LocalDate date = start.plusDays(days);
            if (end != null && date.isAfter(end)) {
                break;
            }
            boolean covered = placement.getCheckedInOn() != null && !placement.getCheckedInOn().isBefore(date);
            if (covered) {
                continue;
            }
            if (date.isAfter(today)) {
                // The first one still to come, unless an earlier one is already due.
                return Optional.of(due != null ? due : new CheckIn(days, date, false));
            }
            due = new CheckIn(days, date, true);
        }
        return Optional.ofNullable(due);
    }
}
