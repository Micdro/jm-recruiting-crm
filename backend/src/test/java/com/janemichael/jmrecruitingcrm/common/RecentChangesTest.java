package com.janemichael.jmrecruitingcrm.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Undo for the last change to a record, and where Later moves a follow-up. */
class RecentChangesTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-14T14:00:00Z"));
    private final RecentChanges changes = new RecentChanges(clock);

    @Test
    void theLastChangeCanBeTakenOnceByThePersonWhoMadeIt() {
        changes.remember(RecentChanges.Kind.CONTACT, 1L, 7L, 100L, "before", "after");

        assertThatThrownBy(() -> changes.take(RecentChanges.Kind.CONTACT, 1L, 8L, String.class))
                .isInstanceOf(ConflictException.class);

        RecentChanges.Change<String> change = changes.take(RecentChanges.Kind.CONTACT, 1L, 7L, String.class);
        assertThat(change.before()).isEqualTo("before");
        assertThat(change.after()).isEqualTo("after");
        assertThat(change.activityId()).isEqualTo(100L);

        assertThatThrownBy(() -> changes.take(RecentChanges.Kind.CONTACT, 1L, 7L, String.class))
                .isInstanceOf(ConflictException.class)
                .hasMessage(RecentChanges.NOTHING_TO_UNDO);
    }

    @Test
    void aNewerChangeToTheSameRecordReplacesTheOlderOne() {
        changes.remember(RecentChanges.Kind.CONTACT, 1L, 7L, 100L, "first", "second");
        changes.remember(RecentChanges.Kind.CONTACT, 1L, 7L, 101L, "second", "third");

        assertThat(changes.take(RecentChanges.Kind.CONTACT, 1L, 7L, String.class).activityId()).isEqualTo(101L);
    }

    @Test
    void recordsOfDifferentKindsAreKeptApart() {
        changes.remember(RecentChanges.Kind.CONTACT, 1L, 7L, 100L, "contact", "contact");
        changes.remember(RecentChanges.Kind.COMPANY, 1L, 7L, 200L, "company", "company");

        assertThat(changes.take(RecentChanges.Kind.CONTACT, 1L, 7L, String.class).activityId()).isEqualTo(100L);
        assertThat(changes.take(RecentChanges.Kind.COMPANY, 1L, 7L, String.class).activityId()).isEqualTo(200L);
    }

    @Test
    void changesExpireAfterTheWindow() {
        changes.remember(RecentChanges.Kind.CANDIDATE, 3L, 7L, 100L, "before", "after");

        clock.advance(RecentChanges.WINDOW.plusSeconds(1));

        assertThatThrownBy(() -> changes.take(RecentChanges.Kind.CANDIDATE, 3L, 7L, String.class))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aBatchGetsItsOwnNumberAndCanBeTakenOnceByThePersonWhoMadeIt() {
        long first = changes.rememberBatch(RecentChanges.Kind.CONTACT_BATCH, 7L, "first batch");
        long second = changes.rememberBatch(RecentChanges.Kind.CONTACT_BATCH, 7L, "second batch");
        assertThat(second).isNotEqualTo(first);

        assertThatThrownBy(() -> changes.takeBatch(RecentChanges.Kind.CONTACT_BATCH, first, 8L, String.class))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> changes.takeBatch(RecentChanges.Kind.COMPANY_BATCH, first, 7L, String.class))
                .isInstanceOf(ConflictException.class);

        assertThat(changes.takeBatch(RecentChanges.Kind.CONTACT_BATCH, first, 7L, String.class)).isEqualTo("first batch");
        assertThatThrownBy(() -> changes.takeBatch(RecentChanges.Kind.CONTACT_BATCH, first, 7L, String.class))
                .isInstanceOf(ConflictException.class);
        assertThat(changes.takeBatch(RecentChanges.Kind.CONTACT_BATCH, second, 7L, String.class)).isEqualTo("second batch");
    }

    @Test
    void batchesExpireLikeSingleChanges() {
        long batch = changes.rememberBatch(RecentChanges.Kind.COMPANY_BATCH, 7L, "batch");

        clock.advance(RecentChanges.WINDOW.plusSeconds(1));

        assertThatThrownBy(() -> changes.takeBatch(RecentChanges.Kind.COMPANY_BATCH, batch, 7L, String.class))
                .isInstanceOf(ConflictException.class)
                .hasMessage(RecentChanges.NOTHING_TO_UNDO);
    }

    @Test
    void tomorrowIsTheNextBusinessDay() {
        LocalDate wednesday = LocalDate.of(2026, 10, 14);
        LocalDate friday = LocalDate.of(2026, 10, 16);
        LocalDate saturday = LocalDate.of(2026, 10, 17);

        assertThat(Later.TOMORROW.from(wednesday)).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(Later.TOMORROW.from(friday)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(Later.TOMORROW.from(saturday)).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    void nextWeekIsTheSameWeekdayAWeekOutAndNeverAWeekend() {
        assertThat(Later.NEXT_WEEK.from(LocalDate.of(2026, 10, 14))).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(Later.NEXT_WEEK.from(LocalDate.of(2026, 10, 16))).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(Later.NEXT_WEEK.from(LocalDate.of(2026, 10, 17))).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(Later.NEXT_WEEK.from(LocalDate.of(2026, 10, 18))).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void historyLinesNameTheDay() {
        assertThat(Later.day(LocalDate.of(2026, 10, 19))).isEqualTo("Mon, Oct 19");
    }

    /** A clock the test can move forward. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("America/New_York");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
