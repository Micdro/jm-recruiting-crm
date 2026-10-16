package com.janemichael.jmrecruitingcrm.desktop;

import com.janemichael.jmrecruitingcrm.auth.CrmLock;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A Windows notification each weekday morning with what's due today for the person who uses this
 * computer (the last one to sign in here). Clicking it opens the CRM.
 *
 * <p>It shows at the set time, or, if the computer was off or asleep then, as soon as the CRM is
 * running in the few hours after. Only counts are read (dates, statuses and owners, which aren't
 * encrypted), so it works while the CRM is locked.
 */
@Component
public class MorningReminder {

    private static final Logger log = LoggerFactory.getLogger(MorningReminder.class);
    static final Duration LATE_BY_AT_MOST = Duration.ofHours(4);

    /** What's due for one person today. {@code waiting}: new contacts not yet in anyone's queue. */
    public record Counts(int touches, int replies, int followUps, int candidates, int companies, int waiting,
                         int interviews, int debriefs) {
    }

    public record Message(String title, String body) {
    }

    /**
     * @param supported a Windows computer running the desktop app
     * @param ready the CRM's icon is in the notification area, which the notification comes from
     */
    public record ReminderStatus(boolean supported, boolean ready, boolean enabled, String time, Message preview) {
    }

    public record Shown(boolean shown, Message message) {
    }

    private final boolean desktop;
    private final String home;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final int dailyTouchTarget;

    public MorningReminder(
            @Value("${app.desktop.enabled:false}") boolean desktop,
            @Value("${app.desktop.home:}") String home,
            NamedParameterJdbcTemplate jdbc,
            Clock clock,
            @Value("${app.outreach.daily-touch-target:30}") int dailyTouchTarget
    ) {
        this.desktop = desktop;
        this.home = home;
        this.jdbc = jdbc;
        this.clock = clock;
        this.dailyTouchTarget = dailyTouchTarget;
    }

    @Scheduled(initialDelayString = "${app.reminder.initial-delay-ms:90000}",
            fixedDelayString = "${app.reminder.interval-ms:60000}")
    public void tick() {
        if (!supported() || !DesktopTray.installed()) {
            return;
        }
        try {
            DesktopSettings settings = settings();
            LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
            if (settings.reminderEmail() == null
                    || !due(now, settings.reminderEnabled(), settings.reminderTime(), settings.reminderShownOn())) {
                return;
            }
            settings.markReminderShown(now.toLocalDate());
            Message message = messageFor(settings.reminderEmail());
            if (message != null) {
                DesktopTray.notify(message.title(), message.body());
            }
        } catch (RuntimeException problem) {
            log.warn("Could not show the morning reminder", problem);
        }
    }

    /** Remembers who uses this computer, so the reminder is about their day. */
    @EventListener
    public void signedIn(CrmLock.AccountCanUnlock event) {
        if (desktop) {
            settings().saveReminderEmail(event.email());
        }
    }

    public ReminderStatus status() {
        DesktopSettings settings = settings();
        String email = settings.reminderEmail();
        return new ReminderStatus(supported(), DesktopTray.installed(), settings.reminderEnabled(),
                settings.reminderTime().toString(), email == null ? null : messageFor(email));
    }

    public ReminderStatus update(boolean enabled, String time) {
        LocalTime at;
        try {
            at = LocalTime.parse(time == null ? "" : time.trim());
        } catch (DateTimeParseException unreadable) {
            throw new BadRequestException("Enter a time like 08:30");
        }
        settings().saveReminder(enabled, at.withSecond(0).withNano(0));
        return status();
    }

    /** Shows today's reminder for {@code email} now, to try it out. */
    public Shown showNow(String email) {
        Message message = messageFor(email);
        boolean shown = message != null && supported() && DesktopTray.notify(message.title(), message.body());
        return new Shown(shown, message);
    }

    /** Whether it's time: a weekday, at or after the set time, not shown yet today, and not hours late. */
    static boolean due(LocalDateTime now, boolean enabled, LocalTime at, LocalDate shownOn) {
        if (!enabled || now.toLocalDate().equals(shownOn)) {
            return false;
        }
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalDateTime start = now.toLocalDate().atTime(at);
        return !now.isBefore(start) && now.isBefore(start.plus(LATE_BY_AT_MOST));
    }

    Message messageFor(String email) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM app_user WHERE lower(email) = lower(:email) AND active",
                new MapSqlParameterSource("email", email), Long.class);
        if (ids.isEmpty()) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("user", ids.getFirst())
                .addValue("today", LocalDate.now(clock));
        Counts counts = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM contact WHERE owner_id = :user AND next_action_date <= :today
                     AND status IN ('NEW', 'IN_SEQUENCE')) AS touches,
                  (SELECT count(*) FROM contact WHERE owner_id = :user AND next_action_date <= :today
                     AND status = 'REPLIED') AS replies,
                  (SELECT count(*) FROM contact WHERE owner_id = :user AND next_action_date <= :today
                     AND status NOT IN ('NEW', 'IN_SEQUENCE', 'REPLIED', 'NOT_INTERESTED', 'DO_NOT_CONTACT')) AS follow_ups,
                  (SELECT count(*) FROM candidate WHERE owner_id = :user AND next_action_date <= :today
                     AND status <> 'DO_NOT_CONTACT') AS candidates,
                  (SELECT count(*) FROM company WHERE owner_id = :user AND follow_up_date <= :today) AS companies,
                  (SELECT count(*) FROM contact WHERE status = 'NEW' AND next_action_date IS NULL
                     AND (owner_id IS NULL OR owner_id = :user)
                     AND company_id NOT IN (SELECT id FROM company WHERE status = 'AI_FOUND')) AS waiting,
                  (SELECT count(*) FROM pipeline_entry p JOIN job_search s ON s.id = p.search_id
                     JOIN candidate c ON c.id = p.candidate_id
                     WHERE coalesce(s.owner_id, c.owner_id) = :user AND p.interview_date = :today
                     AND p.stage NOT IN ('PLACED', 'CLIENT_PASSED', 'WITHDREW')) AS interviews,
                  (SELECT count(*) FROM pipeline_entry p JOIN job_search s ON s.id = p.search_id
                     JOIN candidate c ON c.id = p.candidate_id
                     WHERE coalesce(s.owner_id, c.owner_id) = :user AND p.interview_date < :today
                     AND NOT (p.candidate_debriefed AND p.client_debriefed)
                     AND p.stage NOT IN ('PLACED', 'CLIENT_PASSED', 'WITHDREW')) AS debriefs
                """, params, (row, number) -> new Counts(
                row.getInt("touches"), row.getInt("replies"), row.getInt("follow_ups"),
                row.getInt("candidates"), row.getInt("companies"), row.getInt("waiting"),
                row.getInt("interviews"), row.getInt("debriefs")));
        return compose(counts, dailyTouchTarget);
    }

    static Message compose(Counts counts, int target) {
        int contacts = counts.touches() + counts.replies() + counts.followUps();
        List<String> others = new ArrayList<>();
        if (counts.candidates() > 0) {
            others.add(count(counts.candidates(), "candidate", "candidates"));
        }
        if (counts.companies() > 0) {
            others.add(count(counts.companies(), "client", "clients"));
        }
        List<String> interviews = new ArrayList<>();
        if (counts.interviews() > 0) {
            interviews.add(count(counts.interviews(), "interview", "interviews") + " today");
        }
        if (counts.debriefs() > 0) {
            interviews.add(count(counts.debriefs(), "interview debrief", "interview debriefs") + " to do");
        }

        String title;
        if (contacts > 0) {
            title = "Good morning. " + count(contacts, "contact is", "contacts are") + " due today.";
        } else if (!others.isEmpty() || !interviews.isEmpty()) {
            title = "Good morning. No contacts are due today.";
        } else {
            title = "Good morning. Nothing is due today.";
        }

        List<String> sentences = new ArrayList<>();
        if (contacts > 0) {
            List<String> parts = new ArrayList<>();
            if (counts.touches() > 0) {
                parts.add(count(counts.touches(), "sequence touch", "sequence touches"));
            }
            if (counts.replies() > 0) {
                parts.add(count(counts.replies(), "reply", "replies") + " to answer");
            }
            if (counts.followUps() > 0) {
                parts.add(count(counts.followUps(), "follow-up", "follow-ups"));
            }
            sentences.add(join(parts) + ".");
        }
        if (!interviews.isEmpty()) {
            sentences.add(join(interviews) + ".");
        }
        if (!others.isEmpty()) {
            sentences.add(contacts > 0
                    ? "Also " + join(others) + " to follow up."
                    : "Follow up with " + join(others) + ".");
        }
        int more = Math.min(target - contacts, counts.waiting());
        if (more > 0) {
            sentences.add("Add " + count(more, "new contact", "new contacts") + " on Today to reach " + target + ".");
        }
        sentences.add("Click to open JM CRM.");
        return new Message(title, String.join(" ", sentences));
    }

    private boolean supported() {
        return desktop && DesktopOs.isWindows();
    }

    private DesktopSettings settings() {
        return new DesktopSettings(home == null || home.isBlank() ? DesktopPaths.home() : Path.of(home));
    }

    private static String count(int value, String one, String many) {
        return value + " " + (value == 1 ? one : many);
    }

    /** "a", "a and b", "a, b and c". */
    private static String join(List<String> parts) {
        if (parts.size() <= 1) {
            return String.join("", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }
}
