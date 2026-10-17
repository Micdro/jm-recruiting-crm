package com.janemichael.jmrecruitingcrm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Something to write, the same for every provider: instructions, what to write from, and the
 * fields to fill in. Providers ask for those fields as structured output, so the answer never has
 * to be picked out of free text.
 *
 * @param maxTokens the longest answer, not counting any thinking the model does first
 */
public record AiRequest(String system, String prompt, List<Field> fields, int maxTokens) {

    public enum Type { TEXT, LIST }

    /** One field of the answer. Every field is required; a list may come back empty. */
    public record Field(String name, Type type, String description) {

        public static Field text(String name, String description) {
            return new Field(name, Type.TEXT, description);
        }

        public static Field list(String name, String description) {
            return new Field(name, Type.LIST, description);
        }
    }

    public AiRequest {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("An AI request needs at least one field");
        }
        fields = List.copyOf(fields);
    }

    /**
     * The answer's JSON schema. It suits both Claude's tool input and OpenAI's strict structured
     * output, which needs every property required and no others allowed.
     */
    public ObjectNode schema(ObjectMapper json) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Field field : fields) {
            ObjectNode property = properties.putObject(field.name());
            if (field.type() == Type.LIST) {
                property.put("type", "array");
                property.putObject("items").put("type", "string");
            } else {
                property.put("type", "string");
            }
            property.put("description", field.description());
            required.add(field.name());
        }
        schema.put("additionalProperties", false);
        return schema;
    }
}
