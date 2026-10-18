package com.janemichael.jmrecruitingcrm.search;

import com.janemichael.jmrecruitingcrm.ai.AiRequest;
import com.janemichael.jmrecruitingcrm.ai.AiRequest.Field;
import com.janemichael.jmrecruitingcrm.ai.AiResult;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.ai.AiText;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Writes a job description from a search's brief, for a search opened without one. Nothing is saved
 * here: the draft opens to edit, and saving it keeps it on the search.
 */
@Service
public class JobDescriptionWriter {

    static final String FEATURE = "Writing the job description";
    static final int MAX_TOKENS = 2_000;
    static final String JOB_DESCRIPTION = "jobDescription";

    static final String SYSTEM = """
            You write job descriptions for Jane Michael AI Talent Search, a recruiting firm that places production \
            AI engineers and technical leaders with AI and cybersecurity vendors selling to banks, financial \
            services and insurers. Write for experienced engineers: concrete, plain and short. Use only the search \
            details given: never invent pay, perks, requirements or facts about the client. Don't name the client; \
            describe it from what's given. No em dashes, no hype, no buzzwords.""";

    static final List<Field> FIELDS = List.of(Field.text(JOB_DESCRIPTION, "The job description, 250 to 450 words of "
            + "plain text in short sections, each starting with its heading on its own line: About the role, What "
            + "you'll own, What you'll work with, What we're looking for, and Compensation and location when the "
            + "details give them."));

    public record JobDescriptionDraft(String jobDescription, String provider, int costCents) {
    }

    private final AiService ai;
    private final SearchRepository searches;
    private final TransactionTemplate readOnly;

    public JobDescriptionWriter(AiService ai, SearchRepository searches, PlatformTransactionManager transactionManager) {
        this.ai = ai;
        this.searches = searches;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public JobDescriptionDraft draft(Long searchId) {
        String details = readOnly.execute(status -> describe(searches.findWithDetailsById(searchId)
                .orElseThrow(() -> new NotFoundException("Search not found"))));
        AiResult result = ai.write(FEATURE, new AiRequest(SYSTEM,
                "Write the job description for this search.\n\n" + details, FIELDS, MAX_TOKENS));
        String written = AiText.fit(result.text(JOB_DESCRIPTION), SearchRequest.JOB_DESCRIPTION_MAX);
        if (written == null) {
            throw new BadRequestException("The AI didn't write the job description. Try again.");
        }
        return new JobDescriptionDraft(written, result.provider().label(), result.costCents());
    }

    /** The search as the AI reads it. The client's name stays out; what it does goes in. */
    public static String describe(Search search) {
        StringBuilder text = new StringBuilder();
        line(text, "Role", search.getTitle());
        line(text, "Track", Labels.of(search.getTrack()));
        var company = search.getCompany();
        line(text, "The client does", company.getFocus());
        line(text, "Client's industry", company.getIndustry() == null ? null : Labels.of(company.getIndustry()));
        line(text, "Location", search.getLocation());
        line(text, "Work model", search.getWorkModel() == null ? null : Labels.of(search.getWorkModel()));
        line(text, "Base salary", salaryRange(search.getBaseMin(), search.getBaseMax()));
        line(text, "Production outcome", search.getProductionOutcome());
        line(text, "Technical scope", search.getTechnicalScope());
        line(text, "Accountability", search.getAccountability());
        line(text, "Regulatory constraints", search.getRegulatoryConstraints());
        line(text, "Other notes from intake", search.getNotes());
        return text.toString();
    }

    static String salaryRange(Integer min, Integer max) {
        NumberFormat dollars = NumberFormat.getCurrencyInstance(Locale.US);
        dollars.setMaximumFractionDigits(0);
        if (min != null && max != null) {
            return dollars.format(min) + " to " + dollars.format(max);
        }
        if (min != null) {
            return "from " + dollars.format(min);
        }
        return max == null ? null : "up to " + dollars.format(max);
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(value.trim()).append('\n');
        }
    }
}
