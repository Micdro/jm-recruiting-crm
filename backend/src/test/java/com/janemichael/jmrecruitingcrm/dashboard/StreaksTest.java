package com.janemichael.jmrecruitingcrm.dashboard;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StreaksTest {

    private static final int TARGET = 30;
    /** A Wednesday. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);

    @Test
    void countsWeekdaysInARowAtTheTargetAndSkipsTheWeekend() {
        Map<LocalDate, Integer> touches = new HashMap<>();
        touches.put(WEDNESDAY, 31);
        touches.put(WEDNESDAY.minusDays(1), 30);
        touches.put(WEDNESDAY.minusDays(2), 45);
        // Saturday and Sunday off, then Thursday and Friday at the target.
        touches.put(WEDNESDAY.minusDays(5), 30);
        touches.put(WEDNESDAY.minusDays(6), 32);
        touches.put(WEDNESDAY.minusDays(7), 12);

        assertThat(Streaks.streak(touches, WEDNESDAY, TARGET)).isEqualTo(5);
    }

    @Test
    void todayOnlyAddsOnceTheTargetIsMet() {
        Map<LocalDate, Integer> touches = new HashMap<>();
        touches.put(WEDNESDAY, 10);
        touches.put(WEDNESDAY.minusDays(1), 30);
        touches.put(WEDNESDAY.minusDays(2), 30);

        assertThat(Streaks.streak(touches, WEDNESDAY, TARGET)).isEqualTo(2);
        touches.put(WEDNESDAY, 30);
        assertThat(Streaks.streak(touches, WEDNESDAY, TARGET)).isEqualTo(3);
    }

    @Test
    void aMissedWeekdayEndsIt() {
        Map<LocalDate, Integer> touches = new HashMap<>();
        touches.put(WEDNESDAY.minusDays(1), 30);
        touches.put(WEDNESDAY.minusDays(3), 30);

        assertThat(Streaks.streak(touches, WEDNESDAY, TARGET)).isEqualTo(1);
        assertThat(Streaks.streak(Map.of(), WEDNESDAY, TARGET)).isZero();
    }

    @Test
    void onAWeekendTheStreakRunsToFriday() {
        LocalDate saturday = LocalDate.of(2026, 10, 24);
        Map<LocalDate, Integer> touches = Map.of(saturday.minusDays(1), 30, saturday.minusDays(2), 30);

        assertThat(Streaks.streak(touches, saturday, TARGET)).isEqualTo(2);
        assertThat(Streaks.streak(touches, saturday.plusDays(1), TARGET)).isEqualTo(2);
    }
}
