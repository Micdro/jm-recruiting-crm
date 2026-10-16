package com.janemichael.jmrecruitingcrm.pipeline;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * When an interview counts as over, so the candidate and the client are due a debrief. An
 * interview with a start time is taken to be over an hour after it starts; one without a time,
 * at the end of its day. Candidates no longer in process (placed, passed or withdrawn) are left
 * out: the outcome is already known.
 */
public final class Interviews {

    public static final Duration LENGTH = Duration.ofHours(1);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private Interviews() {
    }

    public static boolean isOver(LocalDate date, LocalTime time, LocalDateTime now) {
        if (date == null) {
            return false;
        }
        LocalDate today = now.toLocalDate();
        if (date.isBefore(today)) {
            return true;
        }
        if (date.isAfter(today) || time == null) {
            return false;
        }
        return !now.isBefore(date.atTime(time).plus(LENGTH));
    }

    /** The interview is over and the candidate, the client or both haven't been debriefed. */
    public static boolean debriefDue(PipelineEntry entry, LocalDateTime now) {
        return entry.getInterviewDate() != null
                && entry.getStage().isActive()
                && !(entry.isCandidateDebriefed() && entry.isClientDebriefed())
                && isOver(entry.getInterviewDate(), entry.getInterviewTime(), now);
    }

    /** The interview is today and hasn't finished yet. */
    public static boolean isLaterToday(PipelineEntry entry, LocalDateTime now) {
        return now.toLocalDate().equals(entry.getInterviewDate())
                && entry.getStage().isActive()
                && !isOver(entry.getInterviewDate(), entry.getInterviewTime(), now);
    }

    /** "Tue, Oct 20 at 2:00 PM", or "Tue, Oct 20" without a time, for history lines. */
    public static String describe(LocalDate date, LocalTime time) {
        return DAY.format(date) + (time == null ? "" : " at " + TIME.format(time));
    }
}
