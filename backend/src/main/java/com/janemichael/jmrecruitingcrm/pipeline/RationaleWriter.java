package com.janemichael.jmrecruitingcrm.pipeline;

import com.janemichael.jmrecruitingcrm.activity.Activity;
import com.janemichael.jmrecruitingcrm.activity.ActivityRepository;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.ai.AiRequest;
import com.janemichael.jmrecruitingcrm.ai.AiRequest.Field;
import com.janemichael.jmrecruitingcrm.ai.AiResult;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.ai.AiText;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.candidate.CandidateEnums;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.EnumCsv;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.search.JobDescriptionWriter;
import com.janemichael.jmrecruitingcrm.search.Search;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drafts the rationale for a candidate in a search: why they fit, as the client reads it on the
 * shortlist and in the blind profile. Nothing is saved here: the draft goes in the rationale box to
 * edit and save.
 */
@Service
public class RationaleWriter {

    static final String FEATURE = "Drafting the rationale";
    static final int MAX_TOKENS = 1_000;
    static final int MAX_CHARS = 2_000;
    static final String RATIONALE = "rationale";
    /** The latest notes from calls and meetings, which often say what the record doesn't yet. */
    static final int RECENT_NOTES = 5;
    private static final Set<ActivityType> NOTE_TYPES = EnumSet.of(ActivityType.CALL, ActivityType.MEETING,
            ActivityType.NOTE);

    static final String SYSTEM = """
            You write the rationale a recruiter at Jane Michael AI Talent Search sends a client with a candidate: \
            why this person fits this search. The client reads it on the shortlist. Base it only on the candidate's \
            record and the search given. Lead with what they owned in production and map it to the role's \
            production outcome and scope. If the search needs something the record doesn't show, say so plainly in \
            one short sentence. 60 to 120 words. No em dashes, no hype, no salary or pay expectations, no personal \
            circumstances, and nothing from the recruiter's notes that a candidate wouldn't want a client to read.""";

    static final List<Field> FIELDS = List.of(Field.text(RATIONALE, "The rationale, in 2 or 3 short paragraphs of "
            + "plain prose."));

    public record RationaleDraft(String rationale, String provider, int costCents) {
    }

    private final AiService ai;
    private final PipelineRepository pipeline;
    private final ActivityRepository activities;
    private final TransactionTemplate readOnly;

    public RationaleWriter(AiService ai, PipelineRepository pipeline, ActivityRepository activities,
                           PlatformTransactionManager transactionManager) {
        this.ai = ai;
        this.pipeline = pipeline;
        this.activities = activities;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public RationaleDraft draft(Long entryId) {
        String prompt = readOnly.execute(status -> {
            PipelineEntry entry = pipeline.findWithDetailsById(entryId)
                    .orElseThrow(() -> new NotFoundException("This candidate isn't in the search"));
            List<String> notes = activities.findForCandidate(entry.getCandidate().getId()).stream()
                    .filter(activity -> NOTE_TYPES.contains(activity.getType()))
                    .map(Activity::getSummary)
                    .filter(summary -> summary != null && !summary.isBlank())
                    .limit(RECENT_NOTES)
                    .toList();
            return prompt(entry.getSearch(), entry.getCandidate(), notes);
        });
        AiResult result = ai.write(FEATURE, new AiRequest(SYSTEM, prompt, FIELDS, MAX_TOKENS));
        String rationale = AiText.fit(result.text(RATIONALE), MAX_CHARS);
        if (rationale == null) {
            throw new BadRequestException("The AI didn't write the rationale. Try again.");
        }
        return new RationaleDraft(rationale, result.provider().label(), result.costCents());
    }

    static String prompt(Search search, Candidate candidate, List<String> notes) {
        StringBuilder text = new StringBuilder("The search:\n").append(JobDescriptionWriter.describe(search));
        text.append("\nThe candidate:\n");
        line(text, "Name", candidate.getName());
        line(text, "Current role", join(candidate.getCurrentTitle(), candidate.getCurrentCompany()));
        line(text, "Location", candidate.getLocation());
        line(text, "Track", candidate.getTrack() == null ? null : Labels.of(candidate.getTrack()));
        line(text, "Covers", EnumCsv.split(candidate.getRoleAreas(), CandidateEnums.RoleArea.class).stream()
                .map(Labels::of).collect(Collectors.joining(", ")));
        line(text, "Experience", candidate.getExperience() == null ? null
                : Labels.of(candidate.getExperience()).replace("Years ", "").replace(" plus", "+").replace(" ", " to ")
                + " years");
        line(text, "Regulated industries", candidate.isRegulatedExperience()
                ? "Has built or run production systems for banks, insurers or other regulated institutions" : null);
        line(text, "Wants to work", candidate.getWorkPreference() == null ? null : Labels.of(candidate.getWorkPreference()));
        line(text, "Willing to relocate", candidate.isWillingToRelocate() ? "yes" : null);
        line(text, "Production evidence", candidate.getProductionEvidence());
        if (!notes.isEmpty()) {
            text.append("Recruiter's latest notes:\n");
            notes.forEach(note -> text.append("- ").append(note.replace("\n", " ")).append('\n'));
        }
        return text.toString();
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return second == null || second.isBlank() ? first : first + " at " + second;
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(value.trim()).append('\n');
        }
    }
}
