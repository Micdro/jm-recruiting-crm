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
 * Encrypts any sensitive values still stored as plain text (rows saved before a field was encrypted,
 * or copied in from an older version) and fills in missing fingerprints. Runs at startup on a server
 * and whenever a desktop copy is unlocked. Safe to run every time.
 */
@Component
@Order(0)
public class EncryptionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionBackfill.class);

    /** Every encrypted column, by table. Keep in step with the @Convert annotations on the entities. */
    static final Map<String, List<String>> ENCRYPTED_COLUMNS = new LinkedHashMap<>();

    static {
        ENCRYPTED_COLUMNS.put("company", List.of("name", "website", "location", "focus", "linkedin_profile", "notes",
                "job_board_token"));
        ENCRYPTED_COLUMNS.put("contact", List.of("name", "title", "email", "phone", "linkedin_url", "notes"));
        ENCRYPTED_COLUMNS.put("candidate", List.of("name", "email", "phone", "linkedin_url", "location",
                "current_title", "current_company", "comp_expectation", "resume_url", "production_evidence", "notes"));
        ENCRYPTED_COLUMNS.put("job_search", List.of("title", "location", "base_min", "base_max",
                "production_outcome", "technical_scope", "accountability", "regulatory_constraints", "notes",
                "job_description"));
        ENCRYPTED_COLUMNS.put("pipeline_entry", List.of("rationale", "interview_label"));
        ENCRYPTED_COLUMNS.put("placement", List.of("base_salary", "fee_amount", "notes", "bill_to_name",
                "bill_to_email", "bill_to_address", "invoice_description"));
        ENCRYPTED_COLUMNS.put("activity", List.of("summary"));
        ENCRYPTED_COLUMNS.put("message_template", List.of("name", "subject", "body"));
        ENCRYPTED_COLUMNS.put("content_post", List.of("title", "body", "notes", "post_url"));
        ENCRYPTED_COLUMNS.put("candidate_file", List.of("file_name"));
        ENCRYPTED_COLUMNS.put("trash_item", List.of("label", "detail", "contents"));
        ENCRYPTED_COLUMNS.put("firm_setting", List.of("value"));
        // A local cache (not synced), encrypted by SignalService rather than by an entity converter.
        ENCRYPTED_COLUMNS.put("job_posting", List.of("title", "location", "url"));
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
            // A desktop copy that is locked or not set up yet. Runs again when it is unlocked.
            return;
        }
        int encrypted = 0;
        for (Map.Entry<String, List<String>> table : ENCRYPTED_COLUMNS.entrySet()) {
            for (String column : table.getValue()) {
                encrypted += encryptColumn(table.getKey(), column);
            }
        }

        int indexed = index("contact", "email", "email_hash") + index("candidate", "email", "email_hash")
                + index("company", "name", "name_hash");

        if (encrypted > 0 || indexed > 0) {
            log.info("Encrypted {} stored values and fingerprinted {} emails and names", encrypted, indexed);
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

    /** Fills in missing fingerprints (for finding duplicates) from the decrypted values. */
    private int index(String table, String column, String hashColumn) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, " + column + " AS value FROM " + table
                        + " WHERE " + column + " IS NOT NULL AND " + hashColumn + " IS NULL");
        if (rows.isEmpty()) {
            return 0;
        }

        List<Object[]> updates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String value = encryptor.decrypt((String) row.get("value"));
            updates.add(new Object[]{encryptor.blindIndex(value), row.get("id")});
        }
        jdbc.batchUpdate("UPDATE " + table + " SET " + hashColumn + " = ? WHERE id = ?", updates);
        return updates.size();
    }
}
