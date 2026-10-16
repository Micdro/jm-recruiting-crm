package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
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

    private static final String PREFIX = "prospects.";
    private static final String API_KEY = "api_key";
    private static final String ENABLED = "enabled";
    private static final String PER_RUN = "per_run";
    private static final String FOCUS = "focus";
    private static final String COMPUTER = "computer";
    private static final String MONTHLY_LIMIT = "monthly_limit";
    private static final String MONTH_SPEND = "month_spend";
    private static final String LAST_RUN = "last_run";

    /** What the finder is set to do. {@code computer} is the computer that runs it on schedule. */
    record Values(String apiKey, boolean enabled, int perRun, String focus, Integer computer, int monthlyLimitDollars) {

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
                number(all.get(MONTHLY_LIMIT), DEFAULT_MONTHLY_LIMIT_DOLLARS, 0, 10_000));
    }

    void saveKey(String apiKey) {
        write(API_KEY, apiKey);
    }

    void saveSchedule(boolean enabled, int perRun, String focus, int monthlyLimitDollars) {
        write(ENABLED, String.valueOf(enabled));
        write(PER_RUN, String.valueOf(perRun));
        write(FOCUS, focus);
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

    void saveRun(Run run) {
        try {
            write(LAST_RUN, json.writeValueAsString(run));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not save the finder's run", impossible);
        }
    }

    /** What the finder has cost in {@code month}, in cents. */
    int spentCents(YearMonth month) {
        String value = read().get(MONTH_SPEND);
        if (value == null || !value.startsWith(month + ":")) {
            return 0;
        }
        return number(value.substring(value.indexOf(':') + 1), 0, 0, Integer.MAX_VALUE);
    }

    void addSpend(YearMonth month, int cents) {
        if (cents > 0) {
            write(MONTH_SPEND, month + ":" + (spentCents(month) + cents));
        }
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
