package com.janemichael.jmrecruitingcrm.report;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.report.WeeklyReport.ByChannel;
import com.janemichael.jmrecruitingcrm.report.WeeklyReport.ByTouch;
import com.janemichael.jmrecruitingcrm.report.WeeklyReport.Person;
import com.janemichael.jmrecruitingcrm.report.WeeklyReport.Week;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out the weekly outreach numbers from the activity history.
 *
 * <ul>
 *   <li>Touches are every logged LinkedIn message, email and call, the same count as the daily target.</li>
 *   <li>A reply is credited to the touch just before it, and to whoever sent that touch: if Dana
 *       replies after Michael's second touch, that's Michael's reply, to touch 2, on LinkedIn.</li>
 *   <li>Touch numbers come from the "Touch 2 of 4" the CRM writes on each touch of a cold sequence.
 *       Follow-ups after a reply have no number, so they count as touches and on their channel but
 *       not toward touches 1 to 4.</li>
 * </ul>
 */
@Service
public class ReportService {

    static final int WEEKS = 12;
    static final int SEQUENCE = 4;
    static final List<String> CHANNELS = List.of("LINKEDIN", "EMAIL", "CALL");
    private static final Pattern TOUCH_NUMBER = Pattern.compile("^Touch (\\d) of \\d");

    /**
     * One thing in a contact's history that the numbers count.
     *
     * @param number the touch's place in a cold sequence (1 to 4), or 0 if it isn't one or is older than the report
     */
    record Event(long contactId, String type, boolean touch, Instant at, Long userId, int number) {
    }

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final CurrentUserService currentUserService;
    private final AppUserRepository userRepository;
    private final Clock clock;
    private final int dailyTouchTarget;

    public ReportService(
            JdbcTemplate jdbc,
            FieldEncryptor encryptor,
            CurrentUserService currentUserService,
            AppUserRepository userRepository,
            Clock clock,
            @Value("${app.outreach.daily-touch-target:30}") int dailyTouchTarget
    ) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
        this.clock = clock;
        this.dailyTouchTarget = dailyTouchTarget;
    }

    public WeeklyReport weekly(boolean team) {
        AppUser me = currentUserService.get();
        LocalDate today = LocalDate.now(clock);
        ZoneId zone = clock.getZone();
        // Only the touches in the report need their number, so only those notes are decrypted.
        Timestamp from = Timestamp.from(firstWeek(today).atStartOfDay(zone).toInstant());
        List<Event> events = jdbc.query("""
                SELECT contact_id, type, touch, occurred_at, user_id,
                       CASE WHEN touch AND occurred_at >= ? THEN summary END AS summary
                FROM activity
                WHERE contact_id IS NOT NULL AND (touch OR type IN ('REPLY', 'MEETING'))
                ORDER BY contact_id, occurred_at, id
                """, (resultSet, row) -> new Event(
                resultSet.getLong("contact_id"),
                resultSet.getString("type"),
                resultSet.getBoolean("touch"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getObject("user_id") == null ? null : resultSet.getLong("user_id"),
                touchNumber(encryptor.decrypt(resultSet.getString("summary")))), from);
        List<AppUser> people = team
                ? userRepository.findAllByOrderByDisplayNameAsc().stream().filter(AppUser::isActive).toList()
                : List.of();
        return compute(events, today, zone, team ? null : me.getId(), people, team ? null : dailyTouchTarget * 5);
    }

    /** The 2 in "Touch 2 of 4: intro email", or 0. */
    static int touchNumber(String summary) {
        if (summary == null) {
            return 0;
        }
        Matcher matcher = TOUCH_NUMBER.matcher(summary);
        if (!matcher.find()) {
            return 0;
        }
        int number = Integer.parseInt(matcher.group(1));
        return number >= 1 && number <= SEQUENCE ? number : 0;
    }

    static LocalDate firstWeek(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(WEEKS - 1);
    }

    /**
     * The numbers for one person ({@code userId}) or everyone (null).
     *
     * @param events each contact's touches, replies and meetings, oldest first, one contact after another
     */
    static WeeklyReport compute(List<Event> events, LocalDate today, ZoneId zone, Long userId, List<AppUser> people,
                                Integer weeklyTarget) {
        LocalDate firstWeek = firstWeek(today);
        int thisWeek = WEEKS - 1;

        int[][] weekTouches = new int[WEEKS][CHANNELS.size()];
        int[] weekReplies = new int[WEEKS];
        int[] weekMeetings = new int[WEEKS];
        int[] weekStarted = new int[WEEKS];
        int[] numberTouches = new int[SEQUENCE + 1];
        int[] numberReplies = new int[SEQUENCE + 1];
        int[] channelTouches = new int[CHANNELS.size()];
        int[] channelReplies = new int[CHANNELS.size()];
        // Per person, this week: touches, replies, meetings.
        Map<Long, int[]> perPerson = new HashMap<>();

        long contact = -1;
        boolean touchedBefore = false;
        // The latest touch since the last reply, if any.
        Event lastTouch = null;

        for (Event event : events) {
            if (event.contactId() != contact) {
                contact = event.contactId();
                touchedBefore = false;
                lastTouch = null;
            }
            int week = weekOf(event.at(), zone, firstWeek, today);

            if (event.touch()) {
                int channel = CHANNELS.indexOf(event.type());
                boolean first = !touchedBefore;
                touchedBefore = true;
                lastTouch = event;
                if (week < 0 || channel < 0 || !mine(event.userId(), userId)) {
                    continue;
                }
                weekTouches[week][channel]++;
                channelTouches[channel]++;
                numberTouches[event.number()]++;
                if (first) {
                    weekStarted[week]++;
                }
                if (week == thisWeek) {
                    count(perPerson, event.userId(), 0);
                }
            } else if ("REPLY".equals(event.type())) {
                Event answered = lastTouch;
                lastTouch = null;
                Long owner = answered != null ? answered.userId() : event.userId();
                if (week < 0 || !mine(owner, userId)) {
                    continue;
                }
                weekReplies[week]++;
                if (week == thisWeek) {
                    count(perPerson, owner, 1);
                }
                // Credited to the touch only if that touch is in the report too, so rates stay within it.
                if (answered != null && weekOf(answered.at(), zone, firstWeek, today) >= 0) {
                    int channel = CHANNELS.indexOf(answered.type());
                    if (channel >= 0) {
                        channelReplies[channel]++;
                    }
                    numberReplies[answered.number()]++;
                }
            } else if ("MEETING".equals(event.type()) && week >= 0 && mine(event.userId(), userId)) {
                weekMeetings[week]++;
                if (week == thisWeek) {
                    count(perPerson, event.userId(), 2);
                }
            }
        }

        List<Week> weeks = new ArrayList<>(WEEKS);
        for (int week = 0; week < WEEKS; week++) {
            weeks.add(new Week(firstWeek.plusWeeks(week), weekTouches[week][0], weekTouches[week][1],
                    weekTouches[week][2], weekReplies[week], weekMeetings[week], weekStarted[week]));
        }
        List<ByTouch> byTouch = new ArrayList<>(SEQUENCE);
        for (int number = 1; number <= SEQUENCE; number++) {
            byTouch.add(new ByTouch(number, numberTouches[number], numberReplies[number]));
        }
        List<ByChannel> byChannel = new ArrayList<>(CHANNELS.size());
        for (int channel = 0; channel < CHANNELS.size(); channel++) {
            byChannel.add(new ByChannel(CHANNELS.get(channel), channelTouches[channel], channelReplies[channel]));
        }
        List<Person> team = people.stream().map(person -> {
            int[] counts = perPerson.getOrDefault(person.getId(), new int[3]);
            return new Person(person.getId(), person.getDisplayName(), counts[0], counts[1], counts[2]);
        }).toList();
        return new WeeklyReport(today, weeklyTarget, weeks, byTouch, byChannel, team);
    }

    /** Which of the report's weeks {@code at} falls in, or -1 if it's outside them. */
    private static int weekOf(Instant at, ZoneId zone, LocalDate firstWeek, LocalDate today) {
        LocalDate day = at.atZone(zone).toLocalDate();
        if (day.isBefore(firstWeek) || day.isAfter(today)) {
            return -1;
        }
        return (int) ChronoUnit.WEEKS.between(firstWeek, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
    }

    private static boolean mine(Long owner, Long userId) {
        return userId == null || Objects.equals(userId, owner);
    }

    private static void count(Map<Long, int[]> perPerson, Long userId, int index) {
        if (userId != null) {
            perPerson.computeIfAbsent(userId, key -> new int[3])[index]++;
        }
    }
}
