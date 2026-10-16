package com.janemichael.jmrecruitingcrm.pipeline;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** When an interview is over, so its debriefs are due. */
class InterviewsTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 20);
    private static final LocalTime TWO_PM = LocalTime.of(14, 0);

    @Test
    void anInterviewWithATimeIsOverAnHourAfterItStarts() {
        assertThat(Interviews.isOver(TUESDAY, TWO_PM, TUESDAY.atTime(13, 0))).isFalse();
        assertThat(Interviews.isOver(TUESDAY, TWO_PM, TUESDAY.atTime(14, 59))).isFalse();
        assertThat(Interviews.isOver(TUESDAY, TWO_PM, TUESDAY.atTime(15, 0))).isTrue();
    }

    @Test
    void anInterviewWithoutATimeIsOverOnceItsDayIs() {
        assertThat(Interviews.isOver(TUESDAY, null, TUESDAY.atTime(23, 59))).isFalse();
        assertThat(Interviews.isOver(TUESDAY, null, TUESDAY.plusDays(1).atTime(8, 0))).isTrue();
    }

    @Test
    void earlierDaysAreOverAndLaterOnesAreNot() {
        LocalDateTime wednesday = TUESDAY.plusDays(1).atTime(9, 0);
        assertThat(Interviews.isOver(TUESDAY, TWO_PM, wednesday)).isTrue();
        assertThat(Interviews.isOver(TUESDAY.plusDays(2), TWO_PM, wednesday)).isFalse();
        assertThat(Interviews.isOver(null, null, wednesday)).isFalse();
    }

    @Test
    void debriefsAreDueUntilBothAreDoneAndOnlyWhileTheCandidateIsInProcess() {
        PipelineEntry entry = new PipelineEntry();
        entry.setStage(PipelineStage.INTERVIEWING);
        entry.setInterviewDate(TUESDAY);
        entry.setInterviewTime(TWO_PM);
        LocalDateTime evening = TUESDAY.atTime(18, 0);

        assertThat(Interviews.debriefDue(entry, TUESDAY.atTime(14, 30))).isFalse();
        assertThat(Interviews.isLaterToday(entry, TUESDAY.atTime(14, 30))).isTrue();
        assertThat(Interviews.debriefDue(entry, evening)).isTrue();
        assertThat(Interviews.isLaterToday(entry, evening)).isFalse();

        entry.setCandidateDebriefed(true);
        assertThat(Interviews.debriefDue(entry, evening)).isTrue();
        entry.setClientDebriefed(true);
        assertThat(Interviews.debriefDue(entry, evening)).isFalse();

        entry.setClientDebriefed(false);
        entry.setStage(PipelineStage.CLIENT_PASSED);
        assertThat(Interviews.debriefDue(entry, evening)).isFalse();
    }

    @Test
    void historyLinesNameTheDayAndTime() {
        assertThat(Interviews.describe(TUESDAY, TWO_PM)).isEqualTo("Tue, Oct 20 at 2:00 PM");
        assertThat(Interviews.describe(TUESDAY, null)).isEqualTo("Tue, Oct 20");
    }
}
