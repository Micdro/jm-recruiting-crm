package com.janemichael.jmrecruitingcrm.common;

import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Where "Later" on the Today queue moves a follow-up. Always counted from today, never onto a weekend. */
public enum Later {
    /** The next business day: Friday moves to Monday. */
    TOMORROW,
    /** The same weekday a week from today. */
    NEXT_WEEK;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    public LocalDate from(LocalDate today) {
        return switch (this) {
            case TOMORROW -> BusinessDays.add(today, 1);
            case NEXT_WEEK -> weekday(today.plusWeeks(1));
        };
    }

    /** "Mon, Oct 19", for the history line. */
    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    private static LocalDate weekday(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) return date.plusDays(2);
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return date.plusDays(1);
        return date;
    }

    /** The body of a snooze request: {"until": "TOMORROW"} or {"until": "NEXT_WEEK"}. */
    public record Request(@NotNull Later until) {
    }
}
