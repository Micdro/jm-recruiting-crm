package com.janemichael.jmrecruitingcrm.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostTheme;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * How the team posts: the style AI writes in, the style of AI pictures, which days each person
 * posts and about what, and each month's focus. One firm_setting row each, encrypted and synced,
 * so every computer plans from the same rules.
 */
@Component
public class ContentPlan {

    static final String PREFIX = "marketing.";
    static final String STYLE = PREFIX + "style";
    static final String PICTURE_STYLE = PREFIX + "picture_style";
    static final String RHYTHM = PREFIX + "rhythm";
    static final String FOCUS = PREFIX + "focus.";

    /** One posting day: who posts, where, and what about. */
    public record Slot(DayOfWeek day, PostTheme theme, PostAccount account, Long ownerId) {
    }

    /** The plan as saved, with the focus of one month. */
    public record Values(String style, String pictureStyle, List<Slot> rhythm, YearMonth month, String focus) {
    }

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    // A computer running an older version still reads a plan saved by a newer one.
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ContentPlan(JdbcTemplate jdbc, FieldEncryptor encryptor) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
    }

    public Values values(YearMonth month) {
        Map<String, String> all = read(month);
        return new Values(all.get(STYLE), all.get(PICTURE_STYLE), rhythm(all.get(RHYTHM)), month,
                all.get(FOCUS + month));
    }

    public void save(String style, String pictureStyle, List<Slot> rhythm, YearMonth month, String focus) {
        write(STYLE, Text.clean(style));
        write(PICTURE_STYLE, Text.clean(pictureStyle));
        try {
            write(RHYTHM, json.writeValueAsString(tidy(rhythm)));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not save the posting days", impossible);
        }
        write(FOCUS + month, Text.clean(focus));
    }

    /** By day, then in the order given; the same person on the same day and account only once. */
    static List<Slot> tidy(List<Slot> rhythm) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Slot> kept = new ArrayList<>();
        for (Slot slot : rhythm == null ? List.<Slot>of() : rhythm) {
            if (slot == null || slot.day() == null) {
                continue;
            }
            Slot filled = new Slot(slot.day(), slot.theme() == null ? PostTheme.OTHER : slot.theme(),
                    slot.account() == null ? PostAccount.PERSONAL : slot.account(), slot.ownerId());
            if (seen.add(filled.day() + "/" + filled.account() + "/" + filled.ownerId())) {
                kept.add(filled);
            }
        }
        kept.sort(Comparator.comparing(Slot::day));
        return kept;
    }

    private List<Slot> rhythm(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return List.of(json.readValue(value, Slot[].class));
        } catch (JsonProcessingException unreadable) {
            return List.of();
        }
    }

    private Map<String, String> read(YearMonth month) {
        Map<String, String> values = new HashMap<>();
        jdbc.query("SELECT name, value FROM firm_setting WHERE name IN (?, ?, ?, ?)", row -> {
            String value = row.getString("value");
            values.put(row.getString("name"), value == null ? null : encryptor.decrypt(value));
        }, STYLE, PICTURE_STYLE, RHYTHM, FOCUS + month);
        return values;
    }

    private void write(String name, String value) {
        jdbc.update("""
                INSERT INTO firm_setting (name, value) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """, name, value == null ? null : encryptor.encrypt(value));
    }
}
