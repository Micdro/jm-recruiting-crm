package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.ai.AiRequest;
import com.janemichael.jmrecruitingcrm.ai.AiRequest.Field;
import com.janemichael.jmrecruitingcrm.ai.AiResult;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.ai.AiText;
import com.janemichael.jmrecruitingcrm.candidate.CandidateFileService.Download;
import com.janemichael.jmrecruitingcrm.candidate.CandidateFileService.Kind;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fills in a candidate record from a resume with AI. Nothing is saved here: the draft opens in the
 * candidate form, and the person checks it and saves.
 */
@Service
public class ResumeReader {

    static final String FEATURE = "Reading the resume";
    static final int MAX_TOKENS = 2_000;
    static final int MAX_MATCHES = 5;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    static final String SYSTEM = """
            You read resumes for Jane Michael AI Talent Search, a recruiting firm that places production AI \
            engineers and technical leaders. Fill in the candidate's record from the resume. Use only what the \
            resume says. When it doesn't say, leave the field empty (an empty string, or an empty list). Never \
            guess an email address, phone number or link. Write plainly, with no em dashes.""";

    static final List<Field> FIELDS = List.of(
            Field.text("name", "The candidate's full name, as written."),
            Field.text("email", "Their email address. Empty if the resume has none."),
            Field.text("phone", "Their phone number as written. Empty if none."),
            Field.text("linkedinUrl", "Their LinkedIn profile address, like https://www.linkedin.com/in/name. "
                    + "Empty if none."),
            Field.text("location", "Where they live, like Austin, TX. Empty if the resume doesn't say."),
            Field.text("currentTitle", "The title of their current or most recent role."),
            Field.text("currentCompany", "The company of their current or most recent role."),
            Field.text("track", "PRODUCTION_AI_ENGINEERING for hands-on engineers who build, ship or run AI and "
                    + "ML systems. TECHNICAL_LEADERSHIP for engineering managers, heads of AI, directors, VPs and "
                    + "CTOs. Empty if neither fits."),
            Field.list("roleAreas", "The parts of production AI they've done, using only these words: DESIGN "
                    + "(architecture and system design), DEPLOY (shipping models and AI features to production, "
                    + "MLOps, infrastructure), OPERATE (running, monitoring, evaluating and improving AI in "
                    + "production). Empty if none."),
            Field.text("experience", "Years of professional work since their first professional role, as one of "
                    + "YEARS_1_2, YEARS_3_4, YEARS_5_7, YEARS_8_9, YEARS_10_PLUS. Empty if you can't tell."),
            Field.text("regulatedExperience", "yes if they've built or run production systems for banks, insurers "
                    + "or other regulated institutions (healthcare, government, defense, pharma). Otherwise no."),
            Field.text("productionEvidence", "Up to 5 lines, each starting with '- ', on what they were "
                    + "answerable for in production: systems they owned, decisions they made, incidents they "
                    + "handled, with the scale or result the resume gives. Specifics over titles. Empty if the "
                    + "resume shows none."));

    public record Match(Long id, String name, String why) {
    }

    /**
     * @param candidate what the resume gives, in the shape the candidate form saves
     * @param matches   candidates already in the CRM who look like the same person
     */
    public record ResumeDraft(CandidateRequest candidate, List<Match> matches, String fileName, String provider,
                              int costCents) {
    }

    private final AiService ai;
    private final CandidateRepository candidates;
    private final CandidateFileService files;
    private final TransactionTemplate readOnly;

    public ResumeReader(AiService ai, CandidateRepository candidates, CandidateFileService files,
                        PlatformTransactionManager transactionManager) {
        this.ai = ai;
        this.candidates = candidates;
        this.files = files;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** Reads a resume dropped in to add a new candidate. */
    public ResumeDraft read(String fileName, byte[] content) {
        String name = CandidateFileService.cleanName(fileName);
        return draft(name, ResumeText.read(name, content), null);
    }

    /** Reads a resume already on a candidate, to fill in what their record is missing. */
    public ResumeDraft read(Long candidateId, Long fileId) {
        Download file = files.download(candidateId, fileId);
        Kind kind = Kind.forContentType(file.contentType());
        if (kind == null) {
            throw new BadRequestException("This file can't be read.");
        }
        return draft(file.fileName(), ResumeText.read(kind, file.content()), candidateId);
    }

    private ResumeDraft draft(String fileName, String text, Long candidateId) {
        AiResult result = ai.write(FEATURE, new AiRequest(SYSTEM,
                "Fill in the candidate's record from this resume.\n\nResume (" + fileName + "):\n\"\"\"\n" + text
                        + "\n\"\"\"", FIELDS, MAX_TOKENS));
        CandidateRequest candidate = candidate(result);
        if (candidate.name() == null) {
            throw new BadRequestException("The AI couldn't find a name in this resume. Check it's a resume, or fill "
                    + "in the candidate by hand.");
        }
        return new ResumeDraft(candidate, matches(candidate, candidateId), fileName, result.provider().label(),
                result.costCents());
    }

    /** The answer as a candidate, every field checked against what the form accepts. */
    static CandidateRequest candidate(AiResult result) {
        List<CandidateEnums.RoleArea> roleAreas = result.list("roleAreas").stream()
                .map(area -> AiText.choice(CandidateEnums.RoleArea.class, area))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new CandidateRequest(
                AiText.fit(result.text("name"), 255),
                email(result.text("email")),
                AiText.fit(result.text("phone"), 50),
                linkedin(result.text("linkedinUrl")),
                AiText.fit(result.text("location"), 255),
                AiText.fit(result.text("currentTitle"), 255),
                AiText.fit(result.text("currentCompany"), 255),
                AiText.choice(SearchEnums.Track.class, result.text("track")),
                null,
                null,
                Boolean.TRUE.equals(AiText.yesNo(result.text("regulatedExperience"))),
                roleAreas,
                null,
                AiText.choice(CandidateEnums.Experience.class, result.text("experience")),
                null,
                null,
                null,
                null,
                AiText.fit(result.text("productionEvidence"), 10_000),
                null,
                null,
                null,
                null,
                null);
    }

    static String email(String answer) {
        String value = answer == null ? "" : answer.trim().replaceFirst("(?i)^mailto:", "");
        return value.length() <= 255 && EMAIL.matcher(value).matches() ? value : null;
    }

    static String linkedin(String answer) {
        String value = answer == null ? "" : answer.trim();
        if (!value.toLowerCase(Locale.ROOT).contains("linkedin.com/") || value.length() > 500 || value.contains(" ")) {
            return null;
        }
        return value.matches("(?i)^https?://.*") ? value : "https://" + value;
    }

    /** Candidates already in the CRM with the same email, LinkedIn or name. */
    List<Match> matches(CandidateRequest draft, Long except) {
        String email = Text.key(draft.email());
        String linkedin = Text.linkedinKey(draft.linkedinUrl());
        String name = nameKey(draft.name());
        return readOnly.execute(status -> candidates.findAllWithOwner().stream()
                .filter(candidate -> !candidate.getId().equals(except))
                .map(candidate -> {
                    String why = email != null && email.equals(Text.key(candidate.getEmail())) ? "same email"
                            : linkedin != null && linkedin.equals(Text.linkedinKey(candidate.getLinkedinUrl()))
                            ? "same LinkedIn"
                            : name != null && name.equals(nameKey(candidate.getName())) ? "same name" : null;
                    return why == null ? null : new Match(candidate.getId(), candidate.getName(), why);
                })
                .filter(Objects::nonNull)
                .limit(MAX_MATCHES)
                .toList());
    }

    static String nameKey(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L} ]", "").replaceAll("\\s+", " ").trim();
        return key.isEmpty() ? null : key;
    }
}
