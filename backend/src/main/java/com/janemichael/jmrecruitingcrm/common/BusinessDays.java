package com.janemichael.jmrecruitingcrm.common;

import java.time.DayOfWeek;
import java.time.LocalDate;

public final class BusinessDays {

    private BusinessDays() {
    }

    /** Adds weekdays to a date, skipping Saturdays and Sundays. */
    public static LocalDate add(LocalDate date, int businessDays) {
        LocalDate result = date;
        int added = 0;
        while (added < businessDays) {
            result = result.plusDays(1);
            if (result.getDayOfWeek() != DayOfWeek.SATURDAY && result.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return result;
    }
}
