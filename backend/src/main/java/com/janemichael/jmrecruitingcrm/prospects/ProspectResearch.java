package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * One search for new prospects: Claude searches the web, then calls record_prospects with what it
 * found. Web search runs on Anthropic's side with dynamic filtering, so Claude writes small programs
 * that keep only the useful part of each result, which keeps the cost down.
 */
@Component
class ProspectResearch {

    static final String MODEL = "claude-sonnet-5-5";
    static final String TOOL = "record_prospects";
    static final int MAX_TOKENS = 16_000;
    /** Requests per run: the first, plus continuations when the API pauses a long turn. */
    static final int MAX_REQUESTS = 12;
    static final int MAX_RETRIES = 3;
    /** A run that's still going after this long is stopped before its next request. */
    static final Duration TIME_LIMIT = Duration.ofMinutes(40);
    /** Web searches one request may run, so no single step can spend much before the CRM checks the cost. */
    static final int SEARCHES_PER_REQUEST = 12;

    /**
     * Kept back for the last step: when the cost so far plus the priciest step yet would leave less
     * than this, the search stops and Claude records what it has found from its notes.
     */
    static final int WRAP_UP_RESERVE_CENTS = 25;
    static final int WRAP_UP_MAX_TOKENS = 8_000;
    /** The wrap-up's instructions and its longest answer, in cents. */
    static final int WRAP_UP_FIXED_CENTS = 10;
    static final int WRAP_UP_MAX_CHARS = 150_000;
    static final int WRAP_UP_MIN_CHARS = 500;
    /** Characters per token, counted low so the wrap-up's cost is overestimated rather than under. */
    static final int CHARS_PER_TOKEN = 3;

    // Prices for Claude Sonnet 5.5, in US dollars per million tokens, and per search.
    static final double INPUT_PER_MILLION = 2.00;
    static final double OUTPUT_PER_MILLION = 10.00;
    static final double CACHE_WRITE_PER_MILLION = 2.50;
    static final double CACHE_READ_PER_MILLION = 0.10;
    static final double PER_SEARCH = 0.01;

    /**
     * @param budgetCents the most this search may cost: what's left of today's limit, or of this
     *                    month's if that's less
     * @param limitName   which limit that is, for "The search reached ...", like "today's $2 limit"
     * @param feedback    the team's recent accepts and dismissals, newest first
     */
    record Request(String apiKey, int count, String focus, List<String> exclude, LocalDate today, int budgetCents,
                   String limitName, List<ProspectSettings.Feedback> feedback) {
    }

    /** Accepted and dismissed companies the instructions mention, at most. */
    static final int FEEDBACK_ACCEPTED_SHOWN = 15;
    static final int FEEDBACK_DISMISSED_SHOWN = 30;

    /** What a run used, added up over its requests. */
    record Usage(long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens, int searches) {

        static final Usage NONE = new Usage(0, 0, 0, 0, 0);

        Usage plus(JsonNode usage) {
            if (usage == null) {
                return this;
            }
            JsonNode server = usage.path("server_tool_use");
            return new Usage(
                    inputTokens + usage.path("input_tokens").asLong(),
                    outputTokens + usage.path("output_tokens").asLong(),
                    cacheWriteTokens + usage.path("cache_creation_input_tokens").asLong(),
                    cacheReadTokens + usage.path("cache_read_input_tokens").asLong(),
                    searches + server.path("web_search_requests").asInt());
        }

        int costCents() {
            double dollars = inputTokens * INPUT_PER_MILLION / 1e6
                    + outputTokens * OUTPUT_PER_MILLION / 1e6
                    + cacheWriteTokens * CACHE_WRITE_PER_MILLION / 1e6
                    + cacheReadTokens * CACHE_READ_PER_MILLION / 1e6
                    + searches * PER_SEARCH;
            return (int) Math.ceil(dollars * 100);
        }
    }

    /** @param note why the search stopped early, when it did (it still saved what it had found) */
    record Outcome(Found.Result found, Usage usage, String note) {
    }

    /** The search couldn't finish. {@code usage} is what it had used (and cost) by then. */
    static class ResearchException extends Exception {
        final Usage usage;

        ResearchException(String message, Usage usage) {
            super(message);
            this.usage = usage;
        }
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final ClaudeApi api;
    private final ObjectMapper json = new ObjectMapper();
    Sleeper sleeper = Thread::sleep;

    ProspectResearch(ClaudeApi api) {
        this.api = api;
    }

    Outcome run(Request request) throws ResearchException, InterruptedException {
        ArrayNode messages = json.createArrayNode();
        messages.addObject().put("role", "user").put("content", userMessage(request));

        Usage usage = Usage.NONE;
        Found.Evidence evidence = new Found.Evidence();
        StringBuilder notes = new StringBuilder();
        String container = null;
        boolean nudged = false;
        int priciestStep = 0;
        int searchLimit = maxSearches(request.count());
        long started = System.nanoTime();

        for (int call = 0; ; call++) {
            String stopping = null;
            if (call >= MAX_REQUESTS) {
                stopping = "took too many steps";
            } else if (Duration.ofNanos(System.nanoTime() - started).compareTo(TIME_LIMIT) > 0) {
                stopping = "took too long";
            } else if (call > 0 && usage.costCents() + priciestStep + WRAP_UP_RESERVE_CENTS > request.budgetCents()) {
                stopping = "reached " + request.limitName();
            } else if (usage.searches() >= searchLimit) {
                stopping = "used all its web searches";
            }
            if (stopping != null) {
                return wrapUp(request, evidence, notes, usage, stopping);
            }

            int maxUses = Math.min(SEARCHES_PER_REQUEST, searchLimit - usage.searches());
            int before = usage.costCents();
            JsonNode response = send(request.apiKey(), body(request, messages, container, maxUses), usage);
            usage = usage.plus(response.get("usage"));
            priciestStep = Math.max(priciestStep, usage.costCents() - before);
            JsonNode content = response.path("content");
            JsonNode containerNode = response.path("container").path("id");
            if (containerNode.isTextual()) {
                container = containerNode.asText();
            }

            JsonNode recorded = null;
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if (type.endsWith("_tool_result")) {
                    evidence.addResult(block);
                } else if (type.equals("text")) {
                    notes.append(block.path("text").asText()).append('\n');
                    // Citations quote the results, so they count as what the searches showed.
                    block.path("citations").forEach(evidence::addResult);
                } else if (type.equals("tool_use") && TOOL.equals(block.path("name").asText())) {
                    recorded = block.path("input");
                }
            }
            if (recorded != null) {
                return new Outcome(Found.read(recorded, evidence, request.count()), usage, null);
            }

            String stop = response.path("stop_reason").asText();
            switch (stop) {
                case "pause_turn" -> messages.addObject().put("role", "assistant").set("content", content);
                case "end_turn" -> {
                    if (nudged) {
                        throw new ResearchException("Claude finished without listing any companies.", usage);
                    }
                    nudged = true;
                    messages.addObject().put("role", "assistant").set("content", content);
                    messages.addObject().put("role", "user").put("content",
                            "Please call " + TOOL + " now with the companies you found (an empty list if none).");
                }
                case "max_tokens" -> {
                    return wrapUp(request, evidence, notes, usage, "ran longer than one step allows");
                }
                case "refusal" -> throw new ResearchException("Claude declined this search.", usage);
                default -> throw new ResearchException("The search stopped unexpectedly (" + stop + ").", usage);
            }
        }
    }

    /**
     * Ends a search that has to stop early: one last request, with no web search, in which Claude
     * records the companies it found from its notes and what its searches returned. That request
     * is sized to fit what's left of the budget, and is skipped when nothing fits.
     *
     * @param why how the search stopped, to finish "The search ..."
     */
    private Outcome wrapUp(Request request, Found.Evidence evidence, CharSequence notes, Usage usage, String why)
            throws ResearchException, InterruptedException {
        String material = material(notes, evidence);
        if (material.isBlank()) {
            throw new ResearchException("The search " + why + " before it found anything.", usage);
        }
        int left = request.budgetCents() - usage.costCents() - WRAP_UP_FIXED_CENTS;
        long affordable = (long) (left / 100.0 / INPUT_PER_MILLION * 1e6) * CHARS_PER_TOKEN;
        int maxChars = (int) Math.max(0, Math.min(WRAP_UP_MAX_CHARS, affordable));
        if (maxChars < WRAP_UP_MIN_CHARS) {
            throw new ResearchException("The search " + why + " with nothing left of its budget to save what it found.",
                    usage);
        }
        if (material.length() > maxChars) {
            material = material.substring(0, maxChars);
        }

        ObjectNode body = json.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", WRAP_UP_MAX_TOKENS);
        body.put("system", systemPrompt(request));
        body.putArray("tools").add(recordTool());
        body.putObject("tool_choice").put("type", "tool").put("name", TOOL);
        body.putArray("messages").addObject().put("role", "user").put("content", wrapUpMessage(request, material));
        String text;
        try {
            text = json.writeValueAsString(body);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }

        JsonNode response = send(request.apiKey(), text, usage);
        usage = usage.plus(response.get("usage"));
        for (JsonNode block : response.path("content")) {
            if (block.path("type").asText().equals("tool_use") && TOOL.equals(block.path("name").asText())) {
                return new Outcome(Found.read(block.path("input"), evidence, request.count()), usage,
                        "The search " + why + ", so it saved what it had found by then.");
            }
        }
        throw new ResearchException("The search " + why + " and couldn't save what it found.", usage);
    }

    /** Claude's notes, then what the searches returned. */
    static String material(CharSequence notes, Found.Evidence evidence) {
        String said = notes.toString().strip();
        String returned = evidence.excerpt().strip();
        if (said.isEmpty() && returned.isEmpty()) {
            return "";
        }
        return "Your notes:\n" + (said.isEmpty() ? "(none)" : said) + "\n\nWhat your searches returned:\n"
                + (returned.isEmpty() ? "(nothing)" : returned);
    }

    static String wrapUpMessage(Request request, String material) {
        return "Your search has to stop now, so there's no more searching. From your notes and what your searches "
                + "returned, below, call " + TOOL + " with up to " + request.count() + " of the best companies. Use "
                + "only details shown below, and leave out anything you'd have to guess.\n\n" + material;
    }

    /** One request, retried when the API is busy or briefly unavailable. */
    private JsonNode send(String apiKey, String body, Usage usage) throws ResearchException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            ClaudeApi.Reply reply;
            try {
                reply = api.messages(apiKey, body);
            } catch (HttpTimeoutException slow) {
                // Not retried: the API may still be working on it, and a second try would be paid for twice.
                throw new ResearchException("The Claude API took too long to answer. Try again later.", usage);
            } catch (IOException unreachable) {
                if (attempt < MAX_RETRIES) {
                    sleeper.sleep(backoff(attempt, null));
                    continue;
                }
                throw new ResearchException("Couldn't reach the Claude API. Check the internet connection.", usage);
            }
            if (reply.ok()) {
                try {
                    return json.readTree(reply.body());
                } catch (JsonProcessingException unreadable) {
                    throw new ResearchException("The Claude API sent an answer the CRM couldn't read.", usage);
                }
            }
            int status = reply.status();
            boolean busy = status == 429 || status == 529 || status >= 500;
            if (busy && attempt < MAX_RETRIES) {
                sleeper.sleep(backoff(attempt, reply.retryAfterSeconds()));
                continue;
            }
            throw new ResearchException(problem(status, apiMessage(reply.body())), usage);
        }
    }

    static long backoff(int attempt, Long retryAfterSeconds) {
        if (retryAfterSeconds != null) {
            return Math.min(retryAfterSeconds, 120) * 1000;
        }
        return 5_000L << attempt;
    }

    /** A sentence for the team about a failed request. */
    static String problem(int status, String apiMessage) {
        return switch (status) {
            case 401 -> "Claude didn't accept the API key. Check it in the finder's settings.";
            case 403 -> "The API key isn't allowed to do this. Check that web search is turned on for your "
                    + "organization in the Claude Console.";
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

    private String apiMessage(String body) {
        try {
            JsonNode message = json.readTree(body).path("error").path("message");
            return message.isTextual() ? message.asText() : null;
        } catch (JsonProcessingException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    // The request ---------------------------------------------------------------

    String body(Request request, ArrayNode messages, String container, int maxUses) {
        ObjectNode body = json.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", systemPrompt(request));
        body.set("messages", messages);
        if (container != null) {
            body.put("container", container);
        }
        ArrayNode tools = body.putArray("tools");
        ObjectNode search = tools.addObject();
        search.put("type", "web_search_20260318");
        search.put("name", "web_search");
        search.put("max_uses", maxUses);
        // Search results Claude's filtering code already read don't need to come back to the CRM.
        search.put("response_inclusion", "excluded");
        tools.add(recordTool());
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Searches for the whole run: about three per company (finding it, then its people), plus a few to start. */
    static int maxSearches(int count) {
        return Math.min(40, 8 + count * 3);
    }

    static String systemPrompt(Request request) {
        String focus = request.focus() == null || request.focus().isBlank()
                ? "None."
                : request.focus().trim();
        return """
                You find new business development prospects for Jane Michael AI Talent Search, a recruiting \
                firm. The firm places Forward Deployed Engineers (FDEs) and AI Platform Engineers \
                (MLOps/LLMOps) with AI and cybersecurity software vendors that sell to banks, insurers and \
                other financial services firms.

                A good prospect:
                - Is a software vendor. Not a bank, insurer or other financial institution itself, not a \
                consultancy, staffing firm or systems integrator, and not a large public tech company.
                - Sells AI or security products to financial institutions: fraud, identity and risk; credit \
                and underwriting decisioning; compliance, AML and KYC; AI agents, workflow and data \
                platforms; or security for AI.
                - Is roughly seed to growth stage (about 20 to 2,000 people), where outside recruiting help \
                is realistic.
                - Shows a reason to hire now, ideally from the last 90 days: open roles for forward \
                deployed, deployment, solutions, field or customer engineers, or for ML, LLM or AI platform \
                or MLOps engineers; a new funding round; or a new bank, credit union, insurer or payments \
                customer or partnership.

                Extra guidance from the team: %s

                How to work:
                - Search efficiently. Combine signals in your queries, for example funding news for AI fraud \
                or compliance startups, or job posts for forward deployed engineers at fintech AI vendors.
                - When you filter search results, print each result's URL with the text you keep, so the \
                links can be checked.
                - Never suggest a company on the user's exclusion list, under any of its names.
                - For each company, find 1 to 3 people who would hire these engineers and work there now: \
                at small companies the founder or CEO; otherwise the CTO, VP or Head of Engineering, VP or \
                Head of Customer, Solutions or Forward Deployed Engineering, or Head of Talent.
                - LinkedIn: give a profile URL only if it appeared in your search results. Never build one \
                from a name.
                - Email and phone: give them only when the person's own work email or direct phone is \
                published on a page you found. Never guess an email from a company's naming pattern, and \
                leave out shared inboxes like info@ or careers@.
                - Leave a field out rather than guess.

                When you have up to %d companies, call %s once with all of them. If you find fewer good \
                ones, record those: quality matters more than count. Today is %s.
                """.formatted(focus, request.count(), TOOL,
                request.today().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)));
    }

    static String userMessage(Request request) {
        String excluded = request.exclude().isEmpty() ? "(none yet)" : String.join("; ", request.exclude());
        String learned = feedbackText(request.feedback());
        return "Find up to " + request.count() + " new prospects.\n\n"
                + (learned.isEmpty() ? "" : learned + "\n\n")
                + "Exclusion list (companies already in the CRM): " + excluded;
    }

    /**
     * What the team made of earlier suggestions: companies they accepted, to find more like them, and
     * ones they dismissed with the reason, to avoid more of the same. Empty when there's nothing yet.
     */
    static String feedbackText(List<ProspectSettings.Feedback> feedback) {
        if (feedback == null || feedback.isEmpty()) {
            return "";
        }
        List<String> accepted = feedback.stream()
                .filter(ProspectSettings.Feedback::accepted)
                .limit(FEEDBACK_ACCEPTED_SHOWN)
                .map(entry -> "- " + entry.company() + (entry.focus() == null ? "" : ": " + entry.focus()))
                .toList();
        List<String> dismissed = feedback.stream()
                .filter(entry -> !entry.accepted())
                .limit(FEEDBACK_DISMISSED_SHOWN)
                .map(entry -> "- " + entry.company() + ": "
                        + (entry.reason() == null ? DismissReason.OTHER.meaning : entry.reason().meaning)
                        + (entry.note() == null ? "" : " (\"" + entry.note() + "\")"))
                .toList();
        StringBuilder text = new StringBuilder("What the team made of earlier suggestions. Look for more companies "
                + "like the accepted ones, and avoid companies dismissed for the same reasons.");
        if (!accepted.isEmpty()) {
            text.append("\n\nAccepted:\n").append(String.join("\n", accepted));
        }
        if (!dismissed.isEmpty()) {
            text.append("\n\nDismissed, and why:\n").append(String.join("\n", dismissed));
        }
        return text.toString();
    }

    private ObjectNode recordTool() {
        ObjectNode tool = json.createObjectNode();
        tool.put("name", TOOL);
        tool.put("description", "Records the prospects you found, all at once, when you're done searching.");
        ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        ObjectNode companies = schema.putObject("properties").putObject("companies");
        companies.put("type", "array");
        ObjectNode company = companies.putObject("items");
        company.put("type", "object");
        ObjectNode fields = company.putObject("properties");
        string(fields, "name", "The company's name.");
        string(fields, "website", "The company's own website.");
        string(fields, "location", "Headquarters city and country.");
        string(fields, "what_they_do", "One sentence on what they sell and to whom.");
        enumField(fields, "industry", "Who they mainly sell to.",
                "BANKS", "FINANCIAL_SERVICES", "INSURANCE", "UNCLEAR");
        ObjectNode segments = fields.putObject("segments");
        segments.put("type", "array");
        segments.put("description", "What their product does.");
        ObjectNode segment = segments.putObject("items");
        segment.put("type", "string");
        segment.putArray("enum").add("FRAUD_IDENTITY_RISK").add("DECISIONING").add("COMPLIANCE_AML")
                .add("WORKFLOWS_PLATFORMS");
        enumField(fields, "hiring_need", "Which engineers they likely need.", "FDE", "AI_PLATFORM", "BOTH", "NOT_SURE");
        string(fields, "signal", "Why now: what happened and when, for example \"Series B of $40M in "
                + "September 2026\" or \"Hiring two forward deployed engineers (posted October 2026)\".");
        ObjectNode sources = fields.putObject("sources");
        sources.put("type", "array");
        sources.put("description", "URLs of the pages that show the signal.");
        sources.putObject("items").put("type", "string");
        ObjectNode people = fields.putObject("people");
        people.put("type", "array");
        people.put("description", "1 to 3 people who would hire these engineers.");
        ObjectNode person = people.putObject("items");
        person.put("type", "object");
        ObjectNode personFields = person.putObject("properties");
        string(personFields, "name", "Full name.");
        string(personFields, "title", "Current job title.");
        enumField(personFields, "role", "Their part in hiring: BUYER decides, INFLUENCER shapes it, "
                + "TALENT runs recruiting.", "BUYER", "INFLUENCER", "TALENT", "OTHER");
        string(personFields, "linkedin_url", "Their LinkedIn profile URL, only if it appeared in your search results.");
        string(personFields, "email", "Their own work email, only if published on a page you found.");
        string(personFields, "phone", "Their direct work phone, only if published on a page you found.");
        person.putArray("required").add("name").add("title");
        company.putArray("required").add("name").add("signal").add("sources");
        schema.putArray("required").add("companies");
        return tool;
    }

    private static void string(ObjectNode fields, String name, String description) {
        fields.putObject(name).put("type", "string").put("description", description);
    }

    private static void enumField(ObjectNode fields, String name, String description, String... values) {
        ObjectNode field = fields.putObject(name);
        field.put("type", "string");
        field.put("description", description);
        ArrayNode list = field.putArray("enum");
        for (String value : values) {
            list.add(value);
        }
    }
}
