package com.janemichael.jmrecruitingcrm.placement;

import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.Text;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The firm's own details for invoices: who they come from and how to pay. Shared by the team and
 * synced, one setting per row, each value encrypted.
 */
@Service
public class FirmSettings {

    public static final String DEFAULT_FIRM_NAME = "Jane Michael LLC";
    public static final int DEFAULT_TERMS_DAYS = 30;

    public record InvoiceSettings(
            @NotBlank(message = "Enter the firm's name")
            @Size(max = 200, message = "The firm's name must be 200 characters or fewer")
            String firmName,

            @Size(max = 500, message = "The address must be 500 characters or fewer")
            String address,

            @Email(message = "Enter a valid email address")
            @Size(max = 255, message = "The email must be 255 characters or fewer")
            String email,

            @Size(max = 50, message = "The phone number must be 50 characters or fewer")
            String phone,

            @Size(max = 255, message = "The website must be 255 characters or fewer")
            String website,

            @Size(max = 1000, message = "The payment instructions must be 1000 characters or fewer")
            String paymentInstructions,

            @NotNull(message = "Enter the payment terms in days")
            @Min(value = 0, message = "Payment terms can't be negative")
            @Max(value = 365, message = "Payment terms can be up to 365 days")
            Integer termsDays
    ) {
    }

    private static final String PREFIX = "invoice.";

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;

    public FirmSettings(JdbcTemplate jdbc, FieldEncryptor encryptor) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
    }

    @Transactional(readOnly = true)
    public InvoiceSettings invoiceSettings() {
        Map<String, String> values = new HashMap<>();
        jdbc.query("SELECT name, value FROM firm_setting WHERE name LIKE ?", resultSet -> {
            values.put(resultSet.getString("name").substring(PREFIX.length()), encryptor.decrypt(resultSet.getString("value")));
        }, PREFIX + "%");
        String terms = values.get("terms_days");
        return new InvoiceSettings(
                values.getOrDefault("firm_name", DEFAULT_FIRM_NAME),
                values.get("address"),
                values.get("email"),
                values.get("phone"),
                values.get("website"),
                values.get("payment_instructions"),
                terms == null || !terms.matches("\\d{1,3}") ? DEFAULT_TERMS_DAYS : Integer.parseInt(terms));
    }

    @Transactional
    public InvoiceSettings saveInvoiceSettings(InvoiceSettings settings) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("firm_name", Text.clean(settings.firmName()));
        values.put("address", Text.clean(settings.address()));
        values.put("email", Text.clean(settings.email()));
        values.put("phone", Text.clean(settings.phone()));
        values.put("website", Text.clean(settings.website()));
        values.put("payment_instructions", Text.clean(settings.paymentInstructions()));
        values.put("terms_days", String.valueOf(settings.termsDays()));
        values.forEach((name, value) -> jdbc.update("""
                INSERT INTO firm_setting (name, value) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """, PREFIX + name, value == null ? null : encryptor.encrypt(value)));
        return invoiceSettings();
    }
}
