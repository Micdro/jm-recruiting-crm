package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.EnumCsv;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.DraftRequest;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.DraftResponse;
import com.janemichael.jmrecruitingcrm.prospects.ProspectResearch.Usage;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.Role;
import com.janemichael.jmrecruitingcrm.signals.SignalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Drafts the next outreach message for a contact with Claude, from what the CRM knows about why
 * their company might hire now: the prospect finder's "why now", and open roles on its job board.
 * The person reads it, edits it and sends it themselves. Each draft costs about a cent, counted
 * against the finder's daily and monthly limits.
 */
@Service
public class OpenerDrafter {

    static final String TOOL = "write_message";
    static final int MAX_TOKENS = 1_000;
    /** A draft needs at least this much left of today's and this month's limits. */
    static final int MIN_CENTS = 5;
    /** Connection notes are asked for a little under LinkedIn's limit, so a small overrun still fits. */
    static final int NOTE_LIMIT = 300;
    static final int NOTE_TARGET = 270;
    static final int ROLES_SHOWN = 3;

    /** What the draft is built from, assembled before calling Claude. */
    record Facts(String sender, String firstName, String name, String title, String buyingRole, String company,
                 String focus, String location, String segments, String hiringNeed, String whyNow,
                 List<String> roles, String role, String channel, Integer touch, boolean cold, String example) {
    }

    private final ProspectSettings settings;
    private final ClaudeApi api;
    private final ContactRepository contacts;
    private final SignalService signals;
    private final CurrentUserService currentUser;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public OpenerDrafter(ProspectSettings settings, ClaudeApi api, ContactRepository contacts, SignalService signals,
                         CurrentUserService currentUser, PlatformTransactionManager transactionManager, Clock clock) {
        this.settings = settings;
        this.api = api;
        this.contacts = contacts;
        this.signals = signals;
        this.currentUser = currentUser;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public DraftResponse draft(DraftRequest request) {
        AppUser me = currentUser.get();
        ProspectSettings.Values values = settings.values();
        if (!values.hasKey()) {
            String owner = currentUser.accountOwnerName();
            throw new BadRequestException("Drafting needs a Claude API key. " + (owner == null ? "An admin" : owner)
                    + " can add one on AI Found.");
        }
        LocalDate today = LocalDate.now(clock);
        int leftToday = values.dailyLimitDollars() * 100 - settings.spentCents(today);
        int leftThisMonth = values.monthlyLimitDollars() * 100 - settings.spentCents(YearMonth.from(today));
        if (Math.min(leftToday, leftThisMonth) < MIN_CENTS) {
            throw new ConflictException(leftToday <= leftThisMonth
                    ? "Today's AI limit of $" + values.dailyLimitDollars() + " is used up. Drafting works again tomorrow."
                    : "This month's AI limit of $" + values.monthlyLimitDollars() + " is used up.");
        }

        Facts facts = transaction.execute(status -> facts(request, me));
        Usage usage = Usage.NONE;
        try {
            JsonNode response = ask(values.apiKey(), facts, null);
            usage = usage.plus(response.get("usage"));
            JsonNode written = written(response);
            String body = clean(written.path("body").asText(""));
            if (isNote(facts) && body.length() > NOTE_LIMIT) {
                JsonNode shorter = ask(values.apiKey(), facts, body);
                usage = usage.plus(shorter.get("usage"));
                written = written(shorter);
                body = clean(written.path("body").asText(""));
            }
            if (body.isBlank()) {
                throw new BadRequestException("Claude didn't write a message. Try again.");
            }
            String subject = "EMAIL".equals(facts.channel()) ? clean(written.path("subject").asText("")) : null;
            return new DraftResponse(subject == null || subject.isBlank() ? null : subject, body, basis(facts),
                    usage.costCents());
        } finally {
            settings.addSpend(today, usage.costCents());
        }
    }

    // What the CRM knows ------------------------------------------------------------------

    private Facts facts(DraftRequest request, AppUser me) {
        Contact contact = contacts.findWithDetailsById(request.contactId())
                .orElseThrow(() -> new NotFoundException("Contact not found"));
        Company company = contact.getCompany();
        List<String> roles = new ArrayList<>();
        try {
            signals.companyBoard(company.getId()).roles().stream()
                    .sorted(Comparator.comparing(Role::unreviewed, Comparator.<Boolean>reverseOrder())
                            .thenComparing(Role::publishedAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder())))
                    .limit(ROLES_SHOWN)
                    .map(Role::title)
                    .forEach(roles::add);
        } catch (RuntimeException noBoard) {
            // No board or cache on this computer: draft from the rest.
        }
        String segments = EnumCsv.split(company.getSegments(), CompanyEnums.Segment.class).stream()
                .map(OpenerDrafter::segment)
                .reduce((first, second) -> first + "; " + second)
                .orElse(null);
        boolean cold = contact.getStatus().isCold();
        return new Facts(
                firstName(me.getDisplayName()),
                firstName(contact.getName()),
                contact.getName(),
                contact.getTitle(),
                contact.getBuyingRole() == null ? null : Labels.of(contact.getBuyingRole()),
                company.getName(),
                company.getFocus(),
                company.getLocation(),
                segments,
                hiringNeed(company.getHiringNeed()),
                whyNow(company.getNotes()),
                roles,
                blankToNull(request.role()),
                request.channel(),
                request.touch(),
                cold,
                blankToNull(request.example()));
    }

    /** The prospect finder's "Why now: ..." paragraph from a company's notes, if it's there. */
    static String whyNow(String notes) {
        if (notes == null) {
            return null;
        }
        for (String paragraph : notes.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            if (trimmed.startsWith("Why now: ")) {
                return trimmed.substring("Why now: ".length()).trim();
            }
        }
        return null;
    }

    /** What the draft was based on, for the person to check before sending. */
    static List<String> basis(Facts facts) {
        List<String> basis = new ArrayList<>();
        if (facts.whyNow() != null) {
            basis.add("Why now: " + facts.whyNow());
        }
        if (facts.role() != null) {
            basis.add("Open role: " + facts.role());
        } else if (!facts.roles().isEmpty()) {
            basis.add("Open roles: " + String.join(", ", facts.roles()));
        }
        if (basis.isEmpty() && facts.focus() != null) {
            basis.add("What they do: " + facts.focus());
        }
        return basis;
    }

    // Asking Claude -----------------------------------------------------------------------

    private JsonNode ask(String apiKey, Facts facts, String tooLong) {
        ObjectNode body = json.createObjectNode();
        body.put("model", ProspectResearch.MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", systemPrompt());
        ObjectNode user = body.putArray("messages").addObject();
        user.put("role", "user");
        user.put("content", userMessage(facts, tooLong));
        body.putArray("tools").add(tool(facts));
        body.putObject("tool_choice").put("type", "tool").put("name", TOOL);

        ClaudeApi.Reply reply;
        try {
            reply = api.messages(apiKey, json.writeValueAsString(body));
        } catch (IOException unreachable) {
            throw new BadRequestException("Couldn't reach the Claude API. Check the internet connection and try again.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("Drafting was interrupted. Try again.");
        }
        if (!reply.ok()) {
            throw new BadRequestException(ProspectResearch.problem(reply.status(), apiMessage(reply.body())));
        }
        try {
            return json.readTree(reply.body());
        } catch (JsonProcessingException unreadable) {
            throw new BadRequestException("The Claude API sent an answer the CRM couldn't read. Try again.");
        }
    }

    private JsonNode written(JsonNode response) {
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && TOOL.equals(block.path("name").asText())) {
                return block.path("input");
            }
        }
        throw new BadRequestException("Claude didn't write a message. Try again.");
    }

    private String apiMessage(String body) {
        try {
            JsonNode message = json.readTree(body).path("error").path("message");
            return message.isTextual() ? message.asText() : null;
        } catch (JsonProcessingException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    static boolean isNote(Facts facts) {
        return "LINKEDIN".equals(facts.channel()) && facts.cold() && Integer.valueOf(1).equals(facts.touch());
    }

    static String systemPrompt() {
        return """
                You write outreach messages for Jane Michael AI Talent Search, a recruiting firm that places \
                Forward Deployed Engineers and AI Platform Engineers (MLOps/LLMOps) with AI and cybersecurity \
                vendors that sell to banks, insurers and other financial services firms. The person sending \
                is a founder of the firm, writing to a possible client: someone who would hire these engineers.

                How to write:
                - Open with the specific reason you're writing now, from the facts given (a funding round, an \
                open role, a new bank or insurer customer). Be concrete; don't flatter.
                - Say plainly what the firm does in one short sentence, tied to that reason.
                - End with one clear ask. For a connection request, ask to connect. Otherwise ask for a \
                15-minute call this week.
                - Short, plain sentences. Sound like a busy person writing to another, not marketing.
                - No em dashes or en dashes. No buzzwords such as "leverage", "synergy", "game-changer", \
                "cutting-edge" or "I hope this finds you well". No emojis. No exclamation marks.
                - Use only the facts given. Never invent numbers, names, customers or candidates. Don't claim \
                to have specific candidates; it's fine to say you know engineers who have done this work.
                - Write to the person by first name and sign with the sender's first name, except in a \
                connection request, which has no sign-off.
                - If the team's own message for this step is given, keep its voice and its points, but make \
                it specific to this person and company.
                """;
    }

    static String userMessage(Facts facts, String tooLong) {
        StringBuilder text = new StringBuilder();
        text.append("Write ").append(kind(facts)).append(".\n\n");
        line(text, "Sender", facts.sender());
        line(text, "Recipient", facts.name() + (facts.title() == null ? "" : ", " + facts.title()));
        line(text, "Recipient's first name", facts.firstName());
        line(text, "Their part in hiring", facts.buyingRole());
        line(text, "Company", facts.company());
        line(text, "What the company does", facts.focus());
        line(text, "Where", facts.location());
        line(text, "Product areas", facts.segments());
        line(text, "Engineers they likely need", facts.hiringNeed());
        line(text, "Why now", facts.whyNow());
        line(text, "The open role this message is about", facts.role());
        if (facts.role() == null && !facts.roles().isEmpty()) {
            line(text, "Open roles on their job board", String.join("; ", facts.roles()));
        }
        if (facts.example() != null) {
            text.append("\nThe team's own message for this step:\n").append(facts.example()).append('\n');
        }
        if (tooLong != null) {
            text.append("\nThis draft is ").append(tooLong.length()).append(" characters, over LinkedIn's limit of ")
                    .append(NOTE_LIMIT).append(". Shorten it to under ").append(NOTE_TARGET).append(":\n")
                    .append(tooLong).append('\n');
        }
        return text.toString();
    }

    static String kind(Facts facts) {
        if ("EMAIL".equals(facts.channel())) {
            return "an email with a subject line, under 120 words";
        }
        if (isNote(facts)) {
            return "a LinkedIn connection request note, under " + NOTE_TARGET + " characters including spaces";
        }
        Integer touch = facts.touch();
        if (!facts.cold() || touch == null) {
            return "a short LinkedIn message, under 80 words";
        }
        return switch (touch) {
            case 2 -> "the first LinkedIn message after they accepted the connection, under 90 words";
            case 3 -> "a short LinkedIn follow-up to an unanswered message, under 60 words";
            default -> "a last, short LinkedIn message that closes the loop politely, under 50 words";
        };
    }

    private ObjectNode tool(Facts facts) {
        ObjectNode tool = json.createObjectNode();
        tool.put("name", TOOL);
        tool.put("description", "The message, ready to send.");
        ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        if ("EMAIL".equals(facts.channel())) {
            properties.putObject("subject").put("type", "string").put("description", "A short, specific subject line.");
        }
        properties.putObject("body").put("type", "string").put("description", "The message itself.");
        schema.putArray("required").add("body");
        return tool;
    }

    // Small helpers -------------------------------------------------------------------------

    /** Dashes become commas, and stray whitespace goes. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s*[\\u2014\\u2013]\\s*", ", ")
                .replaceAll(",\\s*,", ",")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim().split("\\s+")[0];
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    static String segment(CompanyEnums.Segment segment) {
        return switch (segment) {
            case FRAUD_IDENTITY_RISK -> "fraud, identity and risk";
            case DECISIONING -> "credit and underwriting decisioning";
            case COMPLIANCE_AML -> "compliance and AML";
            case WORKFLOWS_PLATFORMS -> "AI workflows and platforms";
        };
    }

    static String hiringNeed(CompanyEnums.HiringNeed need) {
        if (need == null) {
            return null;
        }
        return switch (need) {
            case FDE -> "forward deployed engineers";
            case AI_PLATFORM -> "AI platform engineers (MLOps/LLMOps)";
            case BOTH -> "forward deployed and AI platform engineers";
            case NOT_SURE -> null;
        };
    }
}
