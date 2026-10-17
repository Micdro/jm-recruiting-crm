package com.janemichael.jmrecruitingcrm.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes with OpenAI through the Responses API, using strict structured output so the answer is
 * always the requested fields. Responses aren't stored on OpenAI's side ({@code store: false}).
 */
@Component
class OpenAiProvider implements AiProvider {

    static final String FORMAT = "write";
    /**
     * Room for the model to think before it answers. Thinking is billed as output, and only what's
     * used is charged, so this caps the cost of a call rather than adding to it.
     */
    static final int THINKING_ALLOWANCE = 8_000;

    private final OpenAiApi api;
    private final ObjectMapper json = new ObjectMapper();

    OpenAiProvider(OpenAiApi api) {
        this.api = api;
    }

    @Override
    public AiProviderId id() {
        return AiProviderId.OPENAI;
    }

    @Override
    public AiResult write(String apiKey, String model, AiRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("instructions", request.system());
        body.put("input", request.prompt());
        body.put("max_output_tokens", request.maxTokens() + THINKING_ALLOWANCE);
        body.put("store", false);
        ObjectNode format = body.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", FORMAT);
        format.set("schema", request.schema(json));
        format.put("strict", true);

        OpenAiApi.Reply reply;
        try {
            reply = api.responses(apiKey, json.writeValueAsString(body));
        } catch (IOException unreachable) {
            throw new AiFailure("Couldn't reach the OpenAI API. Check the internet connection and try again.",
                    AiUsage.none(model));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AiFailure("The request to OpenAI was interrupted. Try again.", AiUsage.none(model));
        }
        if (!reply.ok()) {
            throw new AiFailure(problem(reply.status(), apiMessage(reply.body())), AiUsage.none(model));
        }

        JsonNode response = read(reply.body(), model);
        AiUsage usage = usage(model, response.path("usage"));
        if ("incomplete".equals(response.path("status").asText())) {
            throw new AiFailure("OpenAI's answer was cut off. Try again.", usage);
        }
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                String type = part.path("type").asText();
                if ("refusal".equals(type)) {
                    throw new AiFailure("OpenAI declined to write this: " + part.path("refusal").asText(), usage);
                }
                if ("output_text".equals(type)) {
                    try {
                        return new AiResult(AiProviderId.OPENAI, model, json.readTree(part.path("text").asText()), usage);
                    } catch (JsonProcessingException unreadable) {
                        throw new AiFailure("OpenAI sent an answer the CRM couldn't read. Try again.", usage);
                    }
                }
            }
        }
        throw new AiFailure("OpenAI didn't write anything. Try again.", usage);
    }

    /** Picture size: landscape, which LinkedIn's feed shows best. */
    static final String PICTURE_SIZE = "1536x1024";
    static final String PICTURE_QUALITY = "medium";
    /** Charged when OpenAI's answer doesn't say what a picture used: about what a high-quality one costs. */
    static final long PICTURE_FALLBACK_OUTPUT_TOKENS = 3_400;

    /** Makes one picture with OpenAI's image model. Only OpenAI makes pictures; Claude doesn't. */
    AiPicture picture(String apiKey, String prompt) {
        String model = AiModels.PICTURES.id();
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", PICTURE_SIZE);
        body.put("quality", PICTURE_QUALITY);
        body.put("output_format", "jpeg");
        body.put("output_compression", 90);

        OpenAiApi.Reply reply;
        try {
            reply = api.images(apiKey, json.writeValueAsString(body));
        } catch (IOException unreachable) {
            throw new AiFailure("Couldn't reach the OpenAI API. Check the internet connection and try again.",
                    AiUsage.none(model));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AiFailure("The request to OpenAI was interrupted. Try again.", AiUsage.none(model));
        }
        if (!reply.ok()) {
            String message = apiMessage(reply.body());
            throw new AiFailure(reply.status() == 403
                    ? "OpenAI makes pictures only for verified organizations. Verify yours in the OpenAI platform's "
                    + "organization settings, then try again."
                    : problem(reply.status(), message), AiUsage.none(model));
        }

        JsonNode response = read(reply.body(), model);
        JsonNode used = response.path("usage");
        AiUsage usage = used.isObject()
                ? new AiUsage(model, used.path("input_tokens").asLong(), used.path("output_tokens").asLong(), 0, 0)
                : new AiUsage(model, 0, PICTURE_FALLBACK_OUTPUT_TOKENS, 0, 0);
        String encoded = response.path("data").path(0).path("b64_json").asText("");
        if (encoded.isEmpty()) {
            throw new AiFailure("OpenAI didn't make a picture. Try again.", usage);
        }
        try {
            return new AiPicture(java.util.Base64.getDecoder().decode(encoded), "image/jpeg", usage);
        } catch (IllegalArgumentException unreadable) {
            throw new AiFailure("OpenAI sent a picture the CRM couldn't read. Try again.", usage);
        }
    }

    @Override
    public void checkKey(String apiKey) {
        OpenAiApi.Reply reply;
        try {
            reply = api.models(apiKey);
        } catch (IOException unreachable) {
            throw new BadRequestException("Couldn't reach the OpenAI API to check the key. "
                    + "Check the internet connection and try again.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("The key check was interrupted. Try again.");
        }
        if (reply.status() == 401) {
            throw new BadRequestException("OpenAI didn't accept this key. Copy it again from the OpenAI platform.");
        }
        if (reply.status() == 403) {
            throw new BadRequestException("This key isn't allowed to use the OpenAI API.");
        }
    }

    /** Input tokens include any cached ones, counted at the full price (see {@link AiModels.Model}). */
    static AiUsage usage(String model, JsonNode usage) {
        return new AiUsage(model, usage.path("input_tokens").asLong(), usage.path("output_tokens").asLong(), 0, 0);
    }

    /** A sentence for the team about a request OpenAI turned down. */
    static String problem(int status, String apiMessage) {
        return switch (status) {
            case 401 -> "OpenAI didn't accept the API key. The account owner can check it in the AI settings.";
            case 429 -> "OpenAI is busy, or the account's usage limit or credit is used up. Try again later.";
            default -> {
                if (status >= 500) {
                    yield "The OpenAI API had a problem (" + status + "). Try again later.";
                }
                yield apiMessage == null
                        ? "The OpenAI API turned the request down (" + status + ")."
                        : "The OpenAI API turned the request down: " + apiMessage;
            }
        };
    }

    private JsonNode read(String body, String model) {
        try {
            return json.readTree(body);
        } catch (JsonProcessingException unreadable) {
            throw new AiFailure("The OpenAI API sent an answer the CRM couldn't read. Try again.", AiUsage.none(model));
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
