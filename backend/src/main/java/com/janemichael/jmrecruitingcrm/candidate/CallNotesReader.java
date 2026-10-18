package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.ai.AiRequest;
import com.janemichael.jmrecruitingcrm.ai.AiRequest.Field;
import com.janemichael.jmrecruitingcrm.ai.AiResult;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.ai.AiText;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns rough notes from a call into a write-up for the timeline, the next step, and the changes
 * the call brought to the candidate's record. Nothing is saved here: the person checks the draft,
 * unticks what's wrong, and saves it with {@link CandidateService#saveCall}.
 */
@Service
public class CallNotesReader {

    static final String FEATURE = "Reading the call notes";
    static final int MAX_TOKENS = 2_000;
    static final int MAX_NOTES = 20_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US);

    static final String SYSTEM = """
            You turn a recruiter's rough notes from a call with a candidate into a clean record for Jane Michael \
            AI Talent Search, a recruiting firm that places production AI engineers and technical leaders. Use \
            only what the notes say, and never invent details. For anything the notes don't cover or don't \
            change, return an empty string. Write plainly in short sentences, with no em dashes.""";

    static final List<Field> FIELDS = List.of(
            Field.text("summary", "The call for the candidate's timeline, as 2 to 6 short lines that each start "
                    + "with '- ': what they want, what was agreed, and any concerns. Under 1,200 characters. Leave "
                    + "out the next step; it has its own field."),
            Field.text("status", "Only if the call changed it: ACTIVE (looking now), PASSIVE (open to the right "
                    + "role, not looking), NOT_LOOKING, NOT_A_FIT (the recruiter judged them not a fit) or "
                    + "DO_NOT_CONTACT (asked not to be contacted again). Otherwise empty."),
            Field.text("currentTitle", "Their current job title, only if the notes give one different from the "
                    + "record. Otherwise empty."),
            Field.text("currentCompany", "Their current employer, only if the notes give one different from the "
                    + "record. Otherwise empty."),
            Field.text("location", "Where they live, like Austin, TX, only if the notes give a place different "
                    + "from the record. Otherwise empty."),
            Field.text("workPreference", "REMOTE, HYBRID or ONSITE, only if they said what they want. Otherwise "
                    + "empty."),
            Field.text("willingToRelocate", "yes or no, only if relocation came up. Otherwise empty."),
            Field.text("employmentType", "FULL_TIME, CONTRACT or EITHER, only if they said. Otherwise empty."),
            Field.text("compExpectation", "What they want to be paid, in their terms, like $220k base plus "
                    + "equity. Empty if pay didn't come up."),
            Field.text("addEvidence", "Production work they described that the record doesn't have yet: systems "
                    + "they owned in production, decisions they made, incidents they handled, with specifics. One "
                    + "line each, starting with '- '. Empty if none."),
            Field.text("nextStep", "The recruiter's next step as a short instruction, like Send the Acme job "
                    + "description. Empty if there is none."),
            Field.text("nextStepDate", "When to do the next step, as YYYY-MM-DD, worked out from today's date. If "
                    + "the notes give no timing, use the next business day. Empty if there's no next step."),
            Field.text("nextStepType", "How the next step happens: EMAIL, LINKEDIN, CALL, MEETING or OTHER. Empty "
                    + "if there's no next step."));

    public record CallNotesRequest(
            @NotNull(message = "Choose call or meeting")
            ActivityType type,

            @NotBlank(message = "Paste your notes from the call")
            @Size(max = MAX_NOTES, message = "Notes can be up to 20,000 characters")
            String notes
    ) {
    }

    /**
     * @param update   the write-up and changes, ready to save as they are
     * @param nextStep the next step in words, already at the end of the write-up
     */
    public record CallDraft(CallUpdate update, String nextStep, String provider, int costCents) {
    }

    private final AiService ai;
    private final CandidateRepository candidates;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public CallNotesReader(AiService ai, CandidateRepository candidates, PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.ai = ai;
        this.candidates = candidates;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    public CallDraft read(Long candidateId, CallNotesRequest request) {
        if (!CallUpdate.allowed(request.type())) {
            throw new BadRequestException("Notes can come from a call or a meeting.");
        }
        Candidate candidate = readOnly.execute(status -> candidates.findWithOwnerById(candidateId)
                .orElseThrow(() -> new NotFoundException("Candidate not found")));
        LocalDate today = LocalDate.now(clock);
        AiResult result = ai.write(FEATURE, new AiRequest(SYSTEM, prompt(candidate, request, today), FIELDS,
                MAX_TOKENS));

        String nextStep = AiText.fit(result.text("nextStep"), 300);
        String summary = AiText.plain(result.text("summary"));
        if (summary.isEmpty() && nextStep == null) {
            throw new BadRequestException("The AI couldn't make sense of these notes. Add a little more and try again.");
        }
        String written = nextStep == null ? summary : (summary + "\nNext: " + nextStep).trim();
        LocalDate nextDate = nextStep == null ? null : date(result.text("nextStepDate"), today);
        ActionType nextType = nextDate == null ? null
                : Objects.requireNonNullElse(AiText.choice(ActionType.class, result.text("nextStepType")),
                ActionType.CALL);

        CallUpdate update = new CallUpdate(
                request.type(),
                AiText.fit(written, 2_000),
                changed(status(result.text("status")), candidate.getStatus()),
                changedText(AiText.fit(result.text("currentTitle"), 255), candidate.getCurrentTitle()),
                changedText(AiText.fit(result.text("currentCompany"), 255), candidate.getCurrentCompany()),
                changedText(AiText.fit(result.text("location"), 255), candidate.getLocation()),
                changed(AiText.choice(SearchEnums.WorkModel.class, result.text("workPreference")),
                        candidate.getWorkPreference()),
                changed(AiText.yesNo(result.text("willingToRelocate")), candidate.isWillingToRelocate()),
                changed(AiText.choice(CandidateEnums.EmploymentType.class, result.text("employmentType")),
                        candidate.getEmploymentType()),
                changedText(AiText.fit(result.text("compExpectation"), 255), candidate.getCompExpectation()),
                AiText.fit(result.text("addEvidence"), 5_000),
                nextDate,
                nextType,
                null);
        return new CallDraft(update, nextStep, result.provider().label(), result.costCents());
    }

    static String prompt(Candidate candidate, CallNotesRequest request, LocalDate today) {
        StringBuilder text = new StringBuilder();
        text.append("Today is ").append(today.format(DAY)).append(".\n\n");
        text.append(candidate.getName()).append("'s record now:\n");
        line(text, "Status", Labels.of(candidate.getStatus()));
        line(text, "Current title", candidate.getCurrentTitle());
        line(text, "Current company", candidate.getCurrentCompany());
        line(text, "Location", candidate.getLocation());
        line(text, "Work preference", candidate.getWorkPreference() == null ? null
                : Labels.of(candidate.getWorkPreference()));
        line(text, "Willing to relocate", candidate.isWillingToRelocate() ? "yes" : null);
        line(text, "Employment", candidate.getEmploymentType() == null ? null
                : Labels.of(candidate.getEmploymentType()));
        line(text, "Compensation", candidate.getCompExpectation());
        line(text, "Production evidence", candidate.getProductionEvidence());
        line(text, "Next step", candidate.getNextActionDate() == null ? null
                : Labels.of(candidate.getNextActionType()) + " on " + candidate.getNextActionDate().format(DAY));
        text.append("\nNotes from the ").append(request.type() == ActivityType.MEETING ? "meeting" : "call")
                .append(":\n\"\"\"\n").append(request.notes().trim()).append("\n\"\"\"");
        return text.toString();
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    /** Placed comes from a placement, not a call. */
    private static CandidateEnums.Status status(String answer) {
        CandidateEnums.Status status = AiText.choice(CandidateEnums.Status.class, answer);
        return status == CandidateEnums.Status.PLACED ? null : status;
    }

    /** A date from today on, within a year; the next business day if the answer isn't one. */
    static LocalDate date(String answer, LocalDate today) {
        try {
            LocalDate date = LocalDate.parse(answer == null ? "" : answer.trim());
            if (!date.isBefore(today) && date.isBefore(today.plusYears(1))) {
                return date;
            }
        } catch (DateTimeParseException notADate) {
            // Falls through to the next business day.
        }
        LocalDate next = today.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    private static <T> T changed(T proposed, T current) {
        return Objects.equals(proposed, current) ? null : proposed;
    }

    private static String changedText(String proposed, String current) {
        return proposed == null || (current != null && proposed.equalsIgnoreCase(current.trim())) ? null : proposed;
    }
}
