package com.janemichael.jmrecruitingcrm.brief;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.ai.AiRequest;
import com.janemichael.jmrecruitingcrm.ai.AiRequest.Field;
import com.janemichael.jmrecruitingcrm.ai.AiResult;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.ai.AiText;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.BriefResponse;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Due;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Facts;
import com.janemichael.jmrecruitingcrm.brief.BriefDtos.Written;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.Labels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes the Monday brief with AI from the week's facts, once a week for the whole team. It's kept
 * in an encrypted firm_setting row, so it syncs: one person opening Today writes it for everyone.
 */
@Service
public class BriefWriter {

    static final String FEATURE = "Writing the Monday brief";
    static final String PREFIX = "brief.week.";
    static final int MAX_TOKENS = 1_500;
    static final int MAX_LINE = 300;
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMMM d", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    static final String SYSTEM = """
            You write the Monday brief for Jane Michael AI Talent Search, a three-person recruiting firm that \
            places production AI engineers and technical leaders with AI and cybersecurity vendors selling to \
            banks, financial services and insurers. You get the CRM's facts for the week. Write a brief the team \
            reads in under a minute. Use only the facts given: never invent numbers, names or events. Plain \
            words, short lines, no em dashes, no hype. Name the person responsible when the facts give one.""";

    static final List<Field> FIELDS = List.of(
            Field.text("headline", "One sentence on where the firm stands this week, under 25 words."),
            Field.list("lastWeek", "2 to 4 short lines on last week: outreach against target, replies and "
                    + "meetings, candidates who moved forward, placements, posts. Only what the facts show."),
            Field.list("watch", "Up to 5 short lines on what needs attention: candidates waiting too long, "
                    + "searches at risk, follow-ups piling up, debriefs owed, money. Each says what to do. Empty "
                    + "if nothing needs attention."),
            Field.list("focus", "The 3 most important things to do this week, most important first. Each one "
                    + "line, starting with a verb, naming who does it when the facts say."));

    /** The write-up as stored. */
    record Stored(String headline, List<String> lastWeek, List<String> watch, List<String> focus, String writtenAt,
                  String writtenBy, String provider, int costCents) {
    }

    private final BriefFacts facts;
    private final AiService ai;
    private final CurrentUserService currentUser;
    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final Clock clock;
    // A computer running an older version still reads a brief written by a newer one.
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** Set while this computer is writing the brief, so a second request doesn't pay for it twice. */
    private final AtomicBoolean writing = new AtomicBoolean();

    public BriefWriter(BriefFacts facts, AiService ai, CurrentUserService currentUser, JdbcTemplate jdbc,
                       FieldEncryptor encryptor, Clock clock) {
        this.facts = facts;
        this.ai = ai;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    public BriefResponse get() {
        Facts gathered = facts.gather();
        return response(gathered, read(gathered.weekOf()));
    }

    /**
     * Writes this week's brief, or hands back the one already written unless {@code rewrite}.
     *
     * @throws ConflictException when it's already being written here
     */
    public BriefResponse write(boolean rewrite) {
        if (!rewrite) {
            Written existing = read(facts.weekOf());
            if (existing != null) {
                return response(facts.gather(), existing);
            }
        }
        if (!writing.compareAndSet(false, true)) {
            throw new ConflictException("The brief is being written. It shows here in a moment.");
        }
        try {
            Facts gathered = facts.gather();
            AiResult result = ai.write(FEATURE, new AiRequest(SYSTEM, describe(gathered, LocalDate.now(clock)), FIELDS,
                    MAX_TOKENS));
            Written written = new Written(
                    AiText.fit(result.text("headline"), MAX_LINE),
                    lines(result.list("lastWeek"), 4),
                    lines(result.list("watch"), 5),
                    lines(result.list("focus"), 3),
                    clock.instant(),
                    currentUser.get().getDisplayName(),
                    result.provider().label(),
                    result.costCents());
            if (written.headline() == null && written.focus().isEmpty()) {
                throw new BadRequestException("The AI didn't write the brief. Try again.");
            }
            save(gathered.weekOf(), written);
            return response(gathered, written);
        } finally {
            writing.set(false);
        }
    }

    private BriefResponse response(Facts gathered, Written written) {
        AiService.Status status = ai.status();
        AiService.ProviderStatus chosen = status.providers().stream()
                .filter(provider -> provider.id() == status.provider())
                .findFirst()
                .orElse(null);
        return new BriefResponse(gathered.weekOf(), gathered, written, chosen == null ? null : chosen.label(),
                chosen != null && chosen.hasKey(), status.owner());
    }

    /** The facts as the AI reads them. */
    static String describe(Facts facts, LocalDate today) {
        StringBuilder text = new StringBuilder();
        text.append("Today is ").append(today.format(LONG_DAY)).append(". Last week ran from ")
                .append(facts.lastWeek().format(SHORT)).append(" to ").append(facts.weekOf().minusDays(1).format(SHORT))
                .append(".\n\n");

        var outreach = facts.outreach();
        text.append("Last week's outreach: ").append(outreach.touches()).append(" touches (the target is ")
                .append(outreach.target()).append(" a person), ").append(outreach.replies()).append(" replies, ")
                .append(outreach.meetings()).append(" meetings.\n");
        if (!outreach.people().isEmpty()) {
            text.append("Touches by person: ");
            text.append(String.join("; ", outreach.people().stream()
                    .map(person -> person.name() + " " + person.touches()).toList())).append(".\n");
        }

        section(text, "Moved forward last week", facts.moves().stream()
                .map(move -> move.candidate() + " to " + Labels.of(move.stage()) + ", " + move.search() + " at "
                        + move.company())
                .toList());
        section(text, "At one stage too long", facts.stuck().stream()
                .map(stuck -> stuck.candidate() + ", " + Labels.of(stuck.stage()) + " for " + stuck.days() + " days, "
                        + stuck.search() + " at " + stuck.company() + " (" + stuck.owner() + ")")
                .toList());
        section(text, "Open searches that need attention", facts.atRisk().stream()
                .map(risk -> risk.search() + " at " + risk.company() + ": " + risk.reason() + " (" + risk.owner() + ")")
                .toList());
        section(text, "Interviews this week", facts.interviews().stream()
                .map(interview -> interview.on().format(DAY)
                        + (interview.at() == null ? "" : ", " + interview.at().format(TIME)) + ": "
                        + interview.candidate() + (interview.label() == null ? "" : ", " + interview.label()) + ", "
                        + interview.search() + " at " + interview.company())
                .toList());

        text.append("\nInterview debriefs still owed: ").append(facts.debriefsOwed()).append('\n');
        List<String> due = new ArrayList<>();
        for (Due person : facts.due()) {
            due.add(person.name() + " " + person.total() + " (" + person.contacts() + " contacts, "
                    + person.candidates() + " candidates, " + person.companies() + " clients)");
        }
        text.append("Follow-ups due now: ").append(due.isEmpty() ? "none" : String.join("; ", due)).append('\n');
        text.append("Companies with new hiring signals to review: ").append(facts.newSignals()).append('\n');
        var posts = facts.posts();
        text.append("LinkedIn posts: ").append(posts.plannedThisWeek()).append(" planned this week; ")
                .append(posts.postedLastWeek()).append(" posted last week (").append(posts.impressionsLastWeek())
                .append(" impressions, ").append(posts.conversationsLastWeek()).append(" conversations); ")
                .append(posts.missedLastWeek()).append(" planned last week and not posted.\n");
        var money = facts.money();
        text.append("Money: ").append(money.overdueInvoices()).append(" overdue invoices; ")
                .append(money.checkInsDue()).append(" guarantee check-ins due; ").append(money.guaranteesEnding())
                .append(" guarantees ending in the next 30 days.\n");
        return text.toString();
    }

    private static void section(StringBuilder text, String title, List<String> lines) {
        text.append('\n').append(title).append(":\n");
        if (lines.isEmpty()) {
            text.append("- None\n");
        }
        lines.forEach(line -> text.append("- ").append(line).append('\n'));
    }

    /** Plain lines without bullets or numbers, each a sane length, at most {@code max}. */
    static List<String> lines(List<String> answers, int max) {
        return answers.stream()
                .map(line -> line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", ""))
                .map(line -> AiText.fit(line, MAX_LINE))
                .filter(line -> line != null && !line.isBlank())
                .limit(max)
                .toList();
    }

    Written read(LocalDate week) {
        List<String> values = jdbc.queryForList("SELECT value FROM firm_setting WHERE name = ?", String.class,
                PREFIX + week);
        if (values.isEmpty() || values.getFirst() == null) {
            return null;
        }
        try {
            Stored stored = json.readValue(encryptor.decrypt(values.getFirst()), Stored.class);
            return new Written(stored.headline(), orEmpty(stored.lastWeek()), orEmpty(stored.watch()),
                    orEmpty(stored.focus()), stored.writtenAt() == null ? null : Instant.parse(stored.writtenAt()),
                    stored.writtenBy(), stored.provider(), stored.costCents());
        } catch (JsonProcessingException | RuntimeException unreadable) {
            // Written by a version this one can't read: write it again.
            return null;
        }
    }

    private void save(LocalDate week, Written written) {
        String value;
        try {
            value = json.writeValueAsString(new Stored(written.headline(), written.lastWeek(), written.watch(),
                    written.focus(), written.writtenAt().toString(), written.writtenBy(), written.provider(),
                    written.costCents()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not save the brief", impossible);
        }
        jdbc.update("""
                INSERT INTO firm_setting (name, value) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """, PREFIX + week, encryptor.encrypt(value));
    }

    private static List<String> orEmpty(List<String> lines) {
        return lines == null ? List.of() : lines;
    }
}
