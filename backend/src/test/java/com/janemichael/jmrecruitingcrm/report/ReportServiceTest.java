package com.janemichael.jmrecruitingcrm.report;

import com.janemichael.jmrecruitingcrm.report.ReportService.Event;
import com.janemichael.jmrecruitingcrm.report.WeeklyReport.Week;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    // A Wednesday.
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final long MICHAEL = 1;
    private static final long JANE = 2;

    private final List<Event> events = new ArrayList<>();

    @Test
    void weeksRunMondayToSundayEndingWithThisWeekSoFar() {
        WeeklyReport report = ReportService.compute(events, TODAY, ZONE, null, List.of(), null);

        assertThat(report.weeks()).hasSize(12);
        assertThat(report.weeks().getLast().start()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(report.weeks().getFirst().start()).isEqualTo(LocalDate.of(2026, 7, 27));
    }

    @Test
    void countsTouchesByChannelAndCreditsEachReplyToTheTouchBeforeIt() {
        // Dana: LinkedIn touch 1 last week, email touch 2 on Monday, a reply on Tuesday, then a meeting.
        touch(1, "LINKEDIN", 1, "2026-10-06T10:00", MICHAEL);
        touch(1, "EMAIL", 2, "2026-10-12T09:00", MICHAEL);
        event(1, "REPLY", "2026-10-13T08:00", JANE);
        event(1, "MEETING", "2026-10-13T15:00", MICHAEL);
        // Sam: touches 1 to 3 this week, no reply.
        touch(2, "LINKEDIN", 1, "2026-10-12T11:00", MICHAEL);
        touch(2, "EMAIL", 2, "2026-10-13T11:00", MICHAEL);
        touch(2, "CALL", 3, "2026-10-14T11:00", MICHAEL);

        WeeklyReport report = ReportService.compute(events, TODAY, ZONE, MICHAEL, List.of(), 150);

        Week lastWeek = report.weeks().get(10);
        Week thisWeek = report.weeks().get(11);
        assertThat(lastWeek.linkedin()).isEqualTo(1);
        assertThat(lastWeek.started()).isEqualTo(1);
        assertThat(thisWeek.linkedin()).isEqualTo(1);
        assertThat(thisWeek.email()).isEqualTo(2);
        assertThat(thisWeek.call()).isEqualTo(1);
        assertThat(thisWeek.touches()).isEqualTo(4);
        assertThat(thisWeek.started()).isEqualTo(1);
        // Jane logged the reply, but it answered Michael's email.
        assertThat(thisWeek.replies()).isEqualTo(1);
        assertThat(thisWeek.meetings()).isEqualTo(1);

        assertThat(report.byTouch()).extracting(WeeklyReport.ByTouch::touches).containsExactly(2, 2, 1, 0);
        assertThat(report.byTouch()).extracting(WeeklyReport.ByTouch::replies).containsExactly(0, 1, 0, 0);
        assertThat(report.byChannel()).extracting(WeeklyReport.ByChannel::replies).containsExactly(0, 1, 0);
        assertThat(report.weeklyTarget()).isEqualTo(150);
    }

    @Test
    void onePersonsNumbersLeaveOutTheirTeammatesTouches() {
        touch(1, "LINKEDIN", 1, "2026-10-12T10:00", JANE);
        event(1, "REPLY", "2026-10-12T12:00", MICHAEL);
        touch(2, "EMAIL", 0, "2026-10-13T10:00", MICHAEL);

        WeeklyReport michael = ReportService.compute(events, TODAY, ZONE, MICHAEL, List.of(), 150);
        WeeklyReport team = ReportService.compute(events, TODAY, ZONE, null, List.of(), null);

        assertThat(michael.weeks().getLast().touches()).isEqualTo(1);
        assertThat(michael.weeks().getLast().replies()).isZero();
        assertThat(team.weeks().getLast().touches()).isEqualTo(2);
        assertThat(team.weeks().getLast().replies()).isEqualTo(1);
        assertThat(team.byTouch().getFirst().replies()).isEqualTo(1);
    }

    @Test
    void aFollowUpAfterAReplyCountsAsATouchButNotAsTouchOneToFour() {
        touch(1, "LINKEDIN", 1, "2026-10-12T10:00", MICHAEL);
        event(1, "REPLY", "2026-10-12T12:00", MICHAEL);
        touch(1, "EMAIL", 0, "2026-10-13T10:00", MICHAEL);
        event(1, "REPLY", "2026-10-13T12:00", MICHAEL);

        WeeklyReport report = ReportService.compute(events, TODAY, ZONE, MICHAEL, List.of(), 150);

        assertThat(report.weeks().getLast().touches()).isEqualTo(2);
        assertThat(report.weeks().getLast().replies()).isEqualTo(2);
        assertThat(report.byTouch()).extracting(WeeklyReport.ByTouch::touches).containsExactly(1, 0, 0, 0);
        assertThat(report.byTouch()).extracting(WeeklyReport.ByTouch::replies).containsExactly(1, 0, 0, 0);
        assertThat(report.byChannel()).extracting(WeeklyReport.ByChannel::replies).containsExactly(1, 1, 0);
    }

    @Test
    void historyBeforeTheReportOnlyDecidesWhoWasNew() {
        touch(1, "LINKEDIN", 1, "2026-05-04T10:00", MICHAEL);
        touch(1, "EMAIL", 2, "2026-10-12T10:00", MICHAEL);
        touch(2, "LINKEDIN", 4, "2026-07-20T10:00", MICHAEL);
        event(2, "REPLY", "2026-10-12T10:00", MICHAEL);

        WeeklyReport report = ReportService.compute(events, TODAY, ZONE, MICHAEL, List.of(), 150);

        assertThat(report.weeks().getLast().started()).isZero();
        assertThat(report.weeks().getLast().touches()).isEqualTo(1);
        // The reply counts in its week, but the touch it answered is older than the report.
        assertThat(report.weeks().getLast().replies()).isEqualTo(1);
        assertThat(report.byTouch().get(3).replies()).isZero();
    }

    @Test
    void readsTheTouchNumberFromTheNote() {
        assertThat(ReportService.touchNumber("Touch 2 of 4")).isEqualTo(2);
        assertThat(ReportService.touchNumber("Touch 3 of 4: called the main line")).isEqualTo(3);
        assertThat(ReportService.touchNumber("Followed up on Touch 2 of 4")).isZero();
        assertThat(ReportService.touchNumber("Touch 7 of 4")).isZero();
        assertThat(ReportService.touchNumber(null)).isZero();
    }

    private void touch(long contactId, String type, int number, String at, long userId) {
        events.add(new Event(contactId, type, true, LocalDateTime.parse(at).atZone(ZONE).toInstant(), userId, number));
    }

    private void event(long contactId, String type, String at, long userId) {
        events.add(new Event(contactId, type, false, LocalDateTime.parse(at).atZone(ZONE).toInstant(), userId, 0));
    }
}
