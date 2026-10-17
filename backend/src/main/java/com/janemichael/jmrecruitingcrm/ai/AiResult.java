package com.janemichael.jmrecruitingcrm.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** What a provider wrote, field by field, and what it cost. */
public record AiResult(AiProviderId provider, String model, JsonNode output, AiUsage usage) {

    /** A text field, or "" if the model left it out. */
    public String text(String field) {
        JsonNode value = output.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    /** A list field's non-blank entries, trimmed. */
    public List<String> list(String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : output.path(field)) {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText().trim());
            }
        }
        return values;
    }

    public int costCents() {
        return usage.costCents();
    }
}
