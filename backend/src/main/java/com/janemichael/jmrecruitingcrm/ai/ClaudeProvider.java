package com.janemichael.jmrecruitingcrm.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.prospects.ClaudeApi;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes with Claude through the Messages API. The fields come back as the input of a tool Claude
 * is told to use, the same way the opener drafter gets its messages.
 */
@Component
class ClaudeProvider implements AiProvider {

    static final String TOOL = "write";

    private final ClaudeApi api;
    private final ObjectMapper json = new ObjectMapper();

    ClaudeProvider(ClaudeApi api) {
        this.api = api;
    }

    @Override
    public AiProviderId id() {
        return AiProviderId.CLAUDE;
    }

    @Override
    public AiResult write(String apiKey, String model, AiRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        body.put("system", request.system());
        ObjectNode user = body.putArray("messages").addObject();
        user.put("role", "user");
        user.put("content", request.prompt());
        ObjectNode tool = body.putArray("tools").addObject();
        tool.put("name", TOOL);
        tool.put("description", "What you wrote.");
        tool.set("input_schema", request.schema(json));
        body.putObject("tool_choice").put("type", "tool").put("name", TOOL);

        ClaudeApi.Reply reply;
        try {
            reply = api.messages(apiKey, json.writeValueAsString(body));
        } catch (IOException unreachable) {
            throw new AiFailure("Couldn't reach the Claude API. Check the internet connection and try again.",
                    AiUsage.none(model));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AiFailure("The request to Claude was interrupted. Try again.", AiUsage.none(model));
        }
        if (reply.status() < 200 || reply.status() >= 300) {
            throw new AiFailure(problem(reply.status(), apiMessage(reply.body())), AiUsage.none(model));
        }

        JsonNode response = read(reply.body(), model);
        AiUsage usage = usage(model, response.path("usage"));
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && TOOL.equals(block.path("name").asText())) {
                return new AiResult(AiProviderId.CLAUDE, model, block.path("input"), usage);
            }
        }
        if ("max_tokens".equals(response.path("stop_reason").asText())) {
            throw new AiFailure("Claude's answer was cut off. Try again.", usage);
        }
        throw new AiFailure("Claude didn't write anything. Try again.", usage);
    }

    @Override
    public void checkKey(String apiKey) {
        ClaudeApi.Reply reply;
        try {
            reply = api.models(apiKey);
        } catch (IOException unreachable) {
            throw new BadRequestException("Couldn't reach the Claude API to check the key. "
                    + "Check the internet connection and try again.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("The key check was interrupted. Try again.");
        }
        if (reply.status() == 401) {
            throw new BadRequestException("Claude didn't accept this key. Copy it again from the Claude Console.");
        }
        if (reply.status() == 403) {
            throw new BadRequestException("This key isn't allowed to use the Claude API.");
        }
    }

    static AiUsage usage(String model, JsonNode usage) {
        return new AiUsage(model,
                usage.path("input_tokens").asLong(),
                usage.path("output_tokens").asLong(),
                usage.path("cache_creation_input_tokens").asLong(),
                usage.path("cache_read_input_tokens").asLong());
    }

    /** A sentence for the team about a request Claude turned down. */
    static String problem(int status, String apiMessage) {
        return switch (status) {
            case 401 -> "Claude didn't accept the API key. The account owner can check it in the AI settings.";
            case 429 -> "The Claude API is busy or your usage limit is reached. Try again later.";
            case 529 -> "The Claude API is overloaded right now. Try again later.";
            default -> {
                if (status >= 500) {
                    yield "The Claude API had a problem (" + status + "). Try again later.";
                }
                yield apiMessage == null
                        ? "The Claude API turned the request down (" + status + ")."
                        : "The Claude API turned the request down: " + apiMessage;
            }
        };
    }

    private JsonNode read(String body, String model) {
        try {
            return json.readTree(body);
        } catch (JsonProcessingException unreadable) {
            throw new AiFailure("The Claude API sent an answer the CRM couldn't read. Try again.", AiUsage.none(model));
        }
    }

    private String apiMessage(String body) {
        try {
            JsonNode message = json.readTree(body).path("error").path("message");
            return message.isTextual() ? message.asText() : null;
        } catch (JsonProcessingException | IllegalArgumentException unreadable) {
            return null;
        }
    }
}
