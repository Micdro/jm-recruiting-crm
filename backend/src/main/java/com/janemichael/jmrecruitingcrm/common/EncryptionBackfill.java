package com.janemichael.jmrecruitingcrm.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On startup, encrypts any sensitive values still stored as plain text (for example rows saved before
 * encryption was switched on) and fills in missing email fingerprints. Safe to run every time.
 */
@Component
@Order(0)
public class EncryptionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionBackfill.class);

    /** Every encrypted column, by table. Keep in step with the @Convert annotations on the entities. */
    static final Map<String, List<String>> ENCRYPTED_COLUMNS = new LinkedHashMap<>();

    static {
        ENCRYPTED_COLUMNS.put("company", List.of("notes"));
        ENCRYPTED_COLUMNS.put("contact", List.of("email", "phone", "notes"));
        ENCRYPTED_COLUMNS.put("candidate",
                List.of("email", "phone", "comp_expectation", "resume_url", "production_evidence", "notes"));
        ENCRYPTED_COLUMNS.put("job_search",
                List.of("production_outcome", "technical_scope", "accountability", "regulatory_constraints", "notes"));
        ENCRYPTED_COLUMNS.put("pipeline_entry", List.of("rationale"));
        ENCRYPTED_COLUMNS.put("placement", List.of("base_salary", "notes"));
        ENCRYPTED_COLUMNS.put("activity", List.of("summary"));
    }

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;

    public EncryptionBackfill(JdbcTemplate jdbc, FieldEncryptor encryptor) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!encryptor.isConfigured()) {
            // A desktop copy that hasn't been set up yet. Runs again when setup finishes.
            return;
        }
        int encrypted = 0;
        for (Map.Entry<String, List<String>> table : ENCRYPTED_COLUMNS.entrySet()) {
            for (String column : table.getValue()) {
                encrypted += encryptColumn(table.getKey(), column);
            }
        }

        int indexed = indexEmails("contact") + indexEmails("candidate");

        if (encrypted > 0 || indexed > 0) {
            log.info("Encrypted {} stored values and indexed {} emails", encrypted, indexed);
        }
    }

    private int encryptColumn(String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, " + column + " AS value FROM " + table
                        + " WHERE " + column + " IS NOT NULL AND " + column + " NOT LIKE 'enc:%'");
        if (rows.isEmpty()) {
            return 0;
        }

        List<Object[]> updates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            updates.add(new Object[]{encryptor.encrypt(String.valueOf(row.get("value"))), row.get("id")});
        }
        jdbc.batchUpdate("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", updates);
        return updates.size();
    }

    private int indexEmails(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, email FROM " + table + " WHERE email IS NOT NULL AND email_hash IS NULL");
        if (rows.isEmpty()) {
            return 0;
        }

        List<Object[]> updates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String email = encryptor.decrypt((String) row.get("email"));
            updates.add(new Object[]{encryptor.blindIndex(email), row.get("id")});
        }
        jdbc.batchUpdate("UPDATE " + table + " SET email_hash = ? WHERE id = ?", updates);
        return updates.size();
    }
}
