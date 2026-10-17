package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The prospect finder's settings and its last run, shared by the team: one firm_setting row each,
 * encrypted like the rest, so every computer sees the same key, schedule and results.
 */
@Component
class ProspectSettings {

    static final int DEFAULT_PER_RUN = 10;
    static final int MAX_PER_RUN = 25;
    static final int DEFAULT_MONTHLY_LIMIT_DOLLARS = 30;
    static final int DEFAULT_DAILY_LIMIT_DOLLARS = 2;

    private static final String PREFIX = "prospects.";
    private static final String API_KEY = "api_key";
    private static final String ENABLED = "enabled";
    private static final String PER_RUN = "per_run";
    private static final String FOCUS = "focus";
    private static final String COMPUTER = "computer";
    private static final String MONTHLY_LIMIT = "monthly_limit";
    private static final String MONTH_SPEND = "month_spend";
    private static final String DAILY_LIMIT = "daily_limit";
    private static final String DAY_SPEND = "day_spend";
    private static final String LAST_RUN = "last_run";
    private static final String FEEDBACK = "feedback";
    /** How many recent accepts and dismissals the finder remembers. */
    static final int FEEDBACK_KEPT = 60;

    /** What the finder is set to do. {@code computer} is the computer that runs it on schedule. */
    record Values(String apiKey, boolean enabled, int perRun, String focus, Integer computer, int monthlyLimitDollars,
                  int dailyLimitDollars) {

        boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** The last four characters, so people can tell which key is in use without seeing it. */
        String keyHint() {
            return hasKey() && apiKey.length() > 8 ? apiKey.substring(apiKey.length() - 4) : null;
        }
    }

    /**
     * One run of the finder.
     *
     * @param status    RUNNING, DONE or FAILED
     * @param trigger   SCHEDULED or MANUAL
     * @param found     companies Claude returned
     * @param added     companies added to the CRM (the rest were already in it)
     * @param contacts  people added with them
     * @param withheld  LinkedIn pages, emails and phone numbers left out because the searches didn't show them
     * @param costCents what the run cost, from the API's usage figures
     * @param startedAt epoch milliseconds (finishedAt too), stored as numbers
     */
    record Run(String status, String trigger, long startedAt, Long finishedAt, Integer computer,
               String startedBy, int found, int added, int skipped, int contacts, int withheld, int searches,
               int costCents, String message) {

        static Run started(String trigger, Integer computer, String startedBy, Instant now) {
            return new Run("RUNNING", trigger, now.toEpochMilli(), null, computer, startedBy, 0, 0, 0, 0, 0, 0, 0, null);
        }

        boolean running() {
            return "RUNNING".equals(status);
        }

        Instant started() {
            return Instant.ofEpochMilli(startedAt);
        }

        Instant finished() {
            return finishedAt == null ? null : Instant.ofEpochMilli(finishedAt);
        }
    }

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    // A computer running an older version still reads runs saved by a newer one.
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    ProspectSettings(JdbcTemplate jdbc, FieldEncryptor encryptor) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
    }

    Values values() {
        Map<String, String> all = read();
        return new Values(
                all.get(API_KEY),
                // On by default: adding a key is what turns the finder on.
                !"false".equals(all.get(ENABLED)),
                number(all.get(PER_RUN), DEFAULT_PER_RUN, 1, MAX_PER_RUN),
                all.get(FOCUS),
                all.get(COMPUTER) == null ? null : number(all.get(COMPUTER), 0, 0, Integer.MAX_VALUE),
                number(all.get(MONTHLY_LIMIT), DEFAULT_MONTHLY_LIMIT_DOLLARS, 0, 10_000),
                number(all.get(DAILY_LIMIT), DEFAULT_DAILY_LIMIT_DOLLARS, 0, 1_000));
    }

    void saveKey(String apiKey) {
        write(API_KEY, apiKey);
    }

    void saveSchedule(boolean enabled, int perRun, String focus) {
        write(ENABLED, String.valueOf(enabled));
        write(PER_RUN, String.valueOf(perRun));
        write(FOCUS, focus);
    }

    void saveLimits(int dailyLimitDollars, int monthlyLimitDollars) {
        write(DAILY_LIMIT, String.valueOf(dailyLimitDollars));
        write(MONTHLY_LIMIT, String.valueOf(monthlyLimitDollars));
    }

    void saveComputer(int computer) {
        write(COMPUTER, String.valueOf(computer));
    }

    Run lastRun() {
        String value = read().get(LAST_RUN);
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, Run.class);
        } catch (JsonProcessingException unreadable) {
            return null;
        }
    }

    /**
     * What the team thought of one suggestion.
     *
     * @param focus  what the company does, for accepted ones
     * @param reason why it was dismissed (null when accepted)
     * @param at     epoch milliseconds
     */
    record Feedback(String company, boolean accepted, String focus, DismissReason reason, String note, long at) {
    }

    /** Recent accepts and dismissals, newest first. */
    List<Feedback> feedback() {
        String value = read().get(FEEDBACK);
        if (value == null) {
            return List.of();
        }
        try {
            return List.of(json.readValue(value, Feedback[].class));
        } catch (JsonProcessingException unreadable) {
            return List.of();
        }
    }

    void addFeedback(Feedback entry) {
        List<Feedback> kept = new ArrayList<>();
        kept.add(entry);
        feedback().stream().limit(FEEDBACK_KEPT - 1L).forEach(kept::add);
        try {
            write(FEEDBACK, json.writeValueAsString(kept));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not save the feedback", impossible);
        }
    }

    void saveRun(Run run) {
        try {
            write(LAST_RUN, json.writeValueAsString(run));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not save the finder's run", impossible);
        }
    }

    /** What the finder has cost in {@code month}, in cents. */
    int spentCents(YearMonth month) {
        return spent(read().get(MONTH_SPEND), month.toString());
    }

    /** What the finder has cost on {@code day}, in cents. */
    int spentCents(LocalDate day) {
        return spent(read().get(DAY_SPEND), day.toString());
    }

    /** Adds a run's cost to the day it started and that day's month. */
    void addSpend(LocalDate day, int cents) {
        if (cents > 0) {
            YearMonth month = YearMonth.from(day);
            write(MONTH_SPEND, month + ":" + (spentCents(month) + cents));
            write(DAY_SPEND, day + ":" + (spentCents(day) + cents));
        }
    }

    /** Spend is stored as "period:cents"; a different period means nothing spent in this one yet. */
    private static int spent(String value, String period) {
        if (value == null || !value.startsWith(period + ":")) {
            return 0;
        }
        return number(value.substring(value.indexOf(':') + 1), 0, 0, Integer.MAX_VALUE);
    }

    private Map<String, String> read() {
        Map<String, String> values = new HashMap<>();
        jdbc.query("SELECT name, value FROM firm_setting WHERE name LIKE ?", row -> {
            String value = row.getString("value");
            values.put(row.getString("name").substring(PREFIX.length()), value == null ? null : encryptor.decrypt(value));
        }, PREFIX + "%");
        return values;
    }

    private void write(String name, String value) {
        jdbc.update("""
                INSERT INTO firm_setting (name, value) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """, PREFIX + name, value == null ? null : encryptor.encrypt(value));
    }

    private static int number(String value, int fallback, int min, int max) {
        if (value == null || !value.trim().matches("\\d{1,9}")) {
            return fallback;
        }
        return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
    }
}
