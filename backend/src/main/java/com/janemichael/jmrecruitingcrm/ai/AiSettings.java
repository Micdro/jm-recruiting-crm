package com.janemichael.jmrecruitingcrm.ai;

import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

/**
 * The AI settings every feature shares: which provider writes, the API keys, and one set of daily
 * and monthly limits for everything AI costs. One firm_setting row each, encrypted and synced, so
 * every computer uses the same key and counts against the same limits. Only the account owner
 * changes them (see {@link AiService}).
 */
@Component
public class AiSettings {

    public static final int DEFAULT_MONTHLY_LIMIT_DOLLARS = 30;
    public static final int DEFAULT_DAILY_LIMIT_DOLLARS = 2;

    // The Claude key and the limits keep the names they had when only the prospect finder used AI,
    // so they carry over, and a computer that hasn't updated yet still reads the same ones.
    static final String CLAUDE_KEY = "prospects.api_key";
    static final String DAILY_LIMIT = "prospects.daily_limit";
    static final String MONTHLY_LIMIT = "prospects.monthly_limit";
    static final String DAY_SPEND = "prospects.day_spend";
    static final String MONTH_SPEND = "prospects.month_spend";
    static final String PROVIDER = "ai.provider";
    static final String OPENAI_KEY = "ai.openai_key";
    static final String CLAUDE_MODEL = "ai.claude_model";
    static final String OPENAI_MODEL = "ai.openai_model";

    /**
     * The settings as saved. Models fall back to each provider's default.
     *
     * @param provider who writes drafts; Claude until the owner picks otherwise
     */
    public record Values(AiProviderId provider, String claudeKey, String openAiKey, String claudeModel,
                         String openAiModel, int dailyLimitDollars, int monthlyLimitDollars) {

        public String key(AiProviderId which) {
            return which == AiProviderId.OPENAI ? openAiKey : claudeKey;
        }

        public String model(AiProviderId which) {
            return which == AiProviderId.OPENAI ? openAiModel : claudeModel;
        }

        public boolean hasKey(AiProviderId which) {
            String key = key(which);
            return key != null && !key.isBlank();
        }

        /** The last four characters, so people can tell which key is in use without seeing it. */
        public String keyHint(AiProviderId which) {
            String key = key(which);
            return hasKey(which) && key.length() > 8 ? key.substring(key.length() - 4) : null;
        }
    }

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;

    public AiSettings(JdbcTemplate jdbc, FieldEncryptor encryptor) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
    }

    public Values values() {
        Map<String, String> all = read();
        return new Values(
                provider(all.get(PROVIDER)),
                all.get(CLAUDE_KEY),
                all.get(OPENAI_KEY),
                model(AiProviderId.CLAUDE, all.get(CLAUDE_MODEL)),
                model(AiProviderId.OPENAI, all.get(OPENAI_MODEL)),
                number(all.get(DAILY_LIMIT), DEFAULT_DAILY_LIMIT_DOLLARS, 0, 1_000),
                number(all.get(MONTHLY_LIMIT), DEFAULT_MONTHLY_LIMIT_DOLLARS, 0, 10_000));
    }

    public void saveKey(AiProviderId which, String apiKey) {
        write(which == AiProviderId.OPENAI ? OPENAI_KEY : CLAUDE_KEY, apiKey);
    }

    public void saveProvider(AiProviderId provider, String model) {
        write(PROVIDER, provider.name());
        write(provider == AiProviderId.OPENAI ? OPENAI_MODEL : CLAUDE_MODEL, model);
    }

    public void saveLimits(int dailyLimitDollars, int monthlyLimitDollars) {
        write(DAILY_LIMIT, String.valueOf(dailyLimitDollars));
        write(MONTHLY_LIMIT, String.valueOf(monthlyLimitDollars));
    }

    /** What AI has cost in {@code month}, in cents. */
    public int spentCents(YearMonth month) {
        return spent(read().get(MONTH_SPEND), month.toString());
    }

    /** What AI has cost on {@code day}, in cents. */
    public int spentCents(LocalDate day) {
        return spent(read().get(DAY_SPEND), day.toString());
    }

    /** Adds a cost to its day and that day's month. */
    public void addSpend(LocalDate day, int cents) {
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

    private static AiProviderId provider(String value) {
        if (value != null) {
            for (AiProviderId id : AiProviderId.values()) {
                if (id.name().equals(value)) {
                    return id;
                }
            }
        }
        return AiProviderId.CLAUDE;
    }

    /** A saved model that's no longer offered falls back to the default, so no call goes out unpriced. */
    private static String model(AiProviderId provider, String saved) {
        return AiModels.find(provider, saved).orElse(AiModels.defaultFor(provider)).id();
    }

    private Map<String, String> read() {
        Map<String, String> values = new HashMap<>();
        jdbc.query("SELECT name, value FROM firm_setting WHERE name IN (?, ?, ?, ?, ?, ?, ?, ?, ?)", row -> {
            String value = row.getString("value");
            values.put(row.getString("name"), value == null ? null : encryptor.decrypt(value));
        }, CLAUDE_KEY, DAILY_LIMIT, MONTHLY_LIMIT, DAY_SPEND, MONTH_SPEND, PROVIDER, OPENAI_KEY, CLAUDE_MODEL,
                OPENAI_MODEL);
        return values;
    }

    private void write(String name, String value) {
        jdbc.update("""
                INSERT INTO firm_setting (name, value) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """, name, value == null ? null : encryptor.encrypt(value));
    }

    private static int number(String value, int fallback, int min, int max) {
        if (value == null || !value.trim().matches("\\d{1,9}")) {
            return fallback;
        }
        return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
    }
}
