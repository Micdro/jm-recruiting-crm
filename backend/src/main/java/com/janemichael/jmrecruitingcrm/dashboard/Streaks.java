package com.janemichael.jmrecruitingcrm.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Streaks: weekdays in a row that each person reached the daily touch target. Weekends don't count
 * either way, and today adds to a streak once the target is met but never ends one, since the day
 * isn't over.
 */
@Component
public class Streaks {

    /** How far back a streak is counted. Longer ones show as this many days. */
    static final int LOOKBACK_DAYS = 120;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int target;

    public Streaks(JdbcTemplate jdbc, Clock clock, @Value("${app.outreach.daily-touch-target:30}") int target) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.target = target;
    }

    /** Each person's current streak, by user id. People without one aren't listed. */
    public Map<Long, Integer> current() {
        LocalDate today = LocalDate.now(clock);
        ZoneId zone = clock.getZone();
        Timestamp from = Timestamp.from(today.minusDays(LOOKBACK_DAYS + 7L).atStartOfDay(zone).toInstant());

        // Counted by the hour in the database, then by day here, in this computer's time zone.
        Map<Long, Map<LocalDate, Integer>> touches = new HashMap<>();
        jdbc.query("""
                SELECT user_id, date_trunc('hour', occurred_at AT TIME ZONE 'UTC') AS hour, count(*) AS touches
                FROM activity
                WHERE touch AND user_id IS NOT NULL AND occurred_at >= ?
                GROUP BY user_id, hour
                """, row -> {
            LocalDate day = row.getTimestamp("hour").toLocalDateTime().atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(zone).toLocalDate();
            touches.computeIfAbsent(row.getLong("user_id"), user -> new HashMap<>())
                    .merge(day, row.getInt("touches"), Integer::sum);
        }, from);

        Map<Long, Integer> streaks = new HashMap<>();
        touches.forEach((user, days) -> {
            int streak = streak(days, today, target);
            if (streak > 0) {
                streaks.put(user, streak);
            }
        });
        return streaks;
    }

    /** The streak ending today (or on the last weekday, while today's target isn't met yet). */
    static int streak(Map<LocalDate, Integer> touches, LocalDate today, int target) {
        LocalDate day = today;
        if (isWeekday(today) && touches.getOrDefault(today, 0) < target) {
            day = today.minusDays(1);
        }
        int streak = 0;
        LocalDate oldest = today.minusDays(LOOKBACK_DAYS);
        while (!day.isBefore(oldest)) {
            if (isWeekday(day)) {
                if (touches.getOrDefault(day, 0) < target) {
                    break;
                }
                streak++;
            }
            day = day.minusDays(1);
        }
        return streak;
    }

    private static boolean isWeekday(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
