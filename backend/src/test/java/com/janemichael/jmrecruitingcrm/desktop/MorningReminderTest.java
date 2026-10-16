package com.janemichael.jmrecruitingcrm.desktop;

import com.janemichael.jmrecruitingcrm.desktop.MorningReminder.Counts;
import com.janemichael.jmrecruitingcrm.desktop.MorningReminder.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class MorningReminderTest {

    private static final LocalTime HALF_EIGHT = LocalTime.of(8, 30);
    // A Monday.
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @Test
    void showsOnceEachWeekdayFromTheSetTimeUntilAFewHoursLate() {
        assertThat(MorningReminder.due(MONDAY.atTime(8, 29), true, HALF_EIGHT, null)).isFalse();
        assertThat(MorningReminder.due(MONDAY.atTime(8, 30), true, HALF_EIGHT, null)).isTrue();
        // The computer was asleep at 8:30 and woke at 11.
        assertThat(MorningReminder.due(MONDAY.atTime(11, 0), true, HALF_EIGHT, null)).isTrue();
        assertThat(MorningReminder.due(MONDAY.atTime(12, 30), true, HALF_EIGHT, null)).isFalse();

        assertThat(MorningReminder.due(MONDAY.atTime(9, 0), true, HALF_EIGHT, MONDAY)).isFalse();
        assertThat(MorningReminder.due(MONDAY.atTime(9, 0), true, HALF_EIGHT, MONDAY.minusDays(3))).isTrue();
        assertThat(MorningReminder.due(MONDAY.atTime(9, 0), false, HALF_EIGHT, null)).isFalse();

        LocalDateTime saturday = MONDAY.minusDays(2).atTime(9, 0);
        assertThat(MorningReminder.due(saturday, true, HALF_EIGHT, null)).isFalse();
    }

    @Test
    void listsTheDayAndSaysHowToReachTheTarget() {
        Message message = MorningReminder.compose(new Counts(18, 2, 4, 3, 1, 40, 0, 0), 30);

        assertThat(message.title()).isEqualTo("Good morning. 24 contacts are due today.");
        assertThat(message.body()).isEqualTo("18 sequence touches, 2 replies to answer and 4 follow-ups. "
                + "Also 3 candidates and 1 client to follow up. "
                + "Add 6 new contacts on Today to reach 30. "
                + "Click to open JM CRM.");
    }

    @Test
    void namesInterviewsTodayAndDebriefsToDo() {
        Message busy = MorningReminder.compose(new Counts(3, 0, 0, 0, 0, 0, 1, 2), 3);
        assertThat(busy.body()).isEqualTo("3 sequence touches. 1 interview today and 2 interview debriefs to do. "
                + "Click to open JM CRM.");

        Message onlyInterviews = MorningReminder.compose(new Counts(0, 0, 0, 0, 0, 0, 2, 0), 30);
        assertThat(onlyInterviews.title()).isEqualTo("Good morning. No contacts are due today.");
        assertThat(onlyInterviews.body()).isEqualTo("2 interviews today. Click to open JM CRM.");
    }

    @Test
    void leavesOutWhatIsntDue() {
        Message one = MorningReminder.compose(new Counts(1, 0, 0, 0, 0, 0, 0, 0), 30);
        assertThat(one.title()).isEqualTo("Good morning. 1 contact is due today.");
        assertThat(one.body()).isEqualTo("1 sequence touch. Click to open JM CRM.");

        Message noContacts = MorningReminder.compose(new Counts(0, 0, 0, 2, 0, 0, 0, 0), 30);
        assertThat(noContacts.title()).isEqualTo("Good morning. No contacts are due today.");
        assertThat(noContacts.body()).isEqualTo("Follow up with 2 candidates. Click to open JM CRM.");

        Message nothing = MorningReminder.compose(new Counts(0, 0, 0, 0, 0, 3, 0, 0), 30);
        assertThat(nothing.title()).isEqualTo("Good morning. Nothing is due today.");
        assertThat(nothing.body()).isEqualTo("Add 3 new contacts on Today to reach 30. Click to open JM CRM.");

        Message full = MorningReminder.compose(new Counts(35, 0, 0, 0, 0, 50, 0, 0), 30);
        assertThat(full.body()).doesNotContain("Add");
    }

    @Test
    void settingsKeepEachChangeMadeThroughAnotherCopy(@TempDir Path home) {
        DesktopSettings first = new DesktopSettings(home);
        DesktopSettings second = new DesktopSettings(home);
        assertThat(first.reminderEnabled()).isTrue();
        assertThat(first.reminderTime()).isEqualTo(DesktopSettings.DEFAULT_REMINDER_TIME);

        first.saveSyncToken("token");
        second.saveReminder(false, LocalTime.of(7, 45));
        second.saveReminderEmail("michael@jane-michael.test");
        first.markReminderShown(MONDAY);

        DesktopSettings reread = new DesktopSettings(home);
        assertThat(reread.syncToken()).isEqualTo("token");
        assertThat(reread.reminderEnabled()).isFalse();
        assertThat(reread.reminderTime()).isEqualTo(LocalTime.of(7, 45));
        assertThat(reread.reminderEmail()).isEqualTo("michael@jane-michael.test");
        assertThat(reread.reminderShownOn()).isEqualTo(MONDAY);
    }
}
