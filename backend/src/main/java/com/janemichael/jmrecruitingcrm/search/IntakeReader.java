package com.janemichael.jmrecruitingcrm.search;

import com.janemichael.jmrecruitingcrm.ai.AiRequest;
import com.janemichael.jmrecruitingcrm.ai.AiRequest.Field;
import com.janemichael.jmrecruitingcrm.ai.AiResult;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.ai.AiText;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.prospects.CompanyNames;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns notes from a client intake call into a search: the role, the terms the notes give, the
 * search brief and a job description. Nothing is saved here: the draft opens in the search form,
 * and the person checks it and opens the search.
 */
@Service
public class IntakeReader {

    static final String FEATURE = "Reading the intake notes";
    static final int MAX_TOKENS = 4_000;
    static final int MAX_NOTES = 20_000;
    static final int MAX_MATCHES = 5;

    static final String SYSTEM = """
            You turn a recruiter's notes from an intake call with a client into a search record and a job \
            description for Jane Michael AI Talent Search, a recruiting firm that places production AI engineers \
            (forward deployed engineers, AI platform and MLOps engineers) and technical leaders with AI and \
            cybersecurity vendors selling to banks, financial services and insurers. Use only what the notes say: \
            never invent pay, names or requirements. When the notes don't cover a field, leave it empty. Write \
            plainly, with no em dashes.""";

    static final List<Field> FIELDS = List.of(
            Field.text("company", "The client company's name as the notes give it. Empty if not named."),
            Field.text("hiringManager", "The hiring manager's full name. Empty if not named."),
            Field.text("title", "The role's title, like Forward Deployed Engineer."),
            Field.text("track", "PRODUCTION_AI_ENGINEERING for hands-on engineering roles. TECHNICAL_LEADERSHIP for "
                    + "engineering managers, heads of, directors, VPs and CTOs."),
            Field.text("location", "Where the role is based, like New York, NY. Empty if not said."),
            Field.text("workModel", "REMOTE, HYBRID or ONSITE. Empty if not said."),
            Field.text("baseMin", "The lowest base salary in US dollars, digits only, like 180000. Empty if pay "
                    + "wasn't discussed."),
            Field.text("baseMax", "The highest base salary in US dollars, digits only. Empty if not said."),
            Field.text("timeline", "ASAP (within 30 days), ONE_TO_TWO_MONTHS, THREE_PLUS_MONTHS or EXPLORATORY "
                    + "(building a pipeline). Empty if not said."),
            Field.text("engagementType", "CONTINGENCY, EXCLUSIVE or RETAINED, only if the terms came up. Otherwise "
                    + "empty."),
            Field.text("feePercent", "The fee as a percent of first-year base, digits only, like 20. Empty if not "
                    + "said."),
            Field.text("productionOutcome", "What must be live and working because this person was hired, in 1 to "
                    + "3 sentences."),
            Field.text("technicalScope", "The systems, stack and customer environments they'll work in."),
            Field.text("accountability", "What they will own, decide and answer for."),
            Field.text("regulatoryConstraints", "Model risk, data residency, audit, vendor due diligence or other "
                    + "regulatory points that shape the role. Empty if none came up."),
            Field.text("notes", "Anything else useful from the call: the interview process, the team, why the role "
                    + "is open, selling points, concerns. Short lines starting with '- '. Empty if nothing else."),
            Field.text("jobDescription", "A job description for candidates, 250 to 450 words of plain text in short "
                    + "sections, each starting with its heading on its own line: About the role, What you'll own, "
                    + "What you'll work with, What we're looking for, and Compensation and location when the notes "
                    + "give them. Don't name the client; describe it instead, like 'an AI security vendor selling "
                    + "to tier-1 banks'."));

    public record IntakeRequest(
            @NotBlank(message = "Paste your notes from the intake call")
            @Size(max = MAX_NOTES, message = "Notes can be up to 20,000 characters")
            String notes,

            /** The client, when the notes are read from its page. */
            Long companyId
    ) {
    }

    public record CompanyMatch(Long id, String name) {
    }

    /**
     * @param search        what the notes give, in the shape the search form saves; company and hiring
     *                      manager are filled in when they match the CRM
     * @param companyHeard  the company as the notes name it
     * @param companies     companies in the CRM that match that name, when there's more than one
     * @param hiringManager the hiring manager as the notes name them, when they don't match a contact
     */
    public record IntakeDraft(SearchRequest search, String companyHeard, List<CompanyMatch> companies,
                              String hiringManager, String provider, int costCents) {
    }

    private final AiService ai;
    private final CompanyRepository companies;
    private final ContactRepository contacts;
    private final TransactionTemplate readOnly;

    public IntakeReader(AiService ai, CompanyRepository companies, ContactRepository contacts,
                        PlatformTransactionManager transactionManager) {
        this.ai = ai;
        this.companies = companies;
        this.contacts = contacts;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public IntakeDraft read(IntakeRequest request) {
        String knownCompany = request.companyId() == null ? null : readOnly.execute(status -> companies
                .findById(request.companyId())
                .map(Company::getName)
                .orElseThrow(() -> new NotFoundException("Company not found")));

        String prompt = (knownCompany == null ? "" : "The client is " + knownCompany + ".\n\n")
                + "Notes from the intake call:\n\"\"\"\n" + request.notes().trim() + "\n\"\"\"";
        AiResult result = ai.write(FEATURE, new AiRequest(SYSTEM, prompt, FIELDS, MAX_TOKENS));

        String title = AiText.fit(result.text("title"), 255);
        if (title == null) {
            throw new BadRequestException("The AI couldn't find the role in these notes. Add the role's title and try "
                    + "again.");
        }
        String companyHeard = AiText.fit(result.text("company"), 255);
        String managerHeard = AiText.fit(result.text("hiringManager"), 255);

        record Resolved(Long companyId, List<CompanyMatch> matches, Long managerId) {
        }
        Resolved resolved = readOnly.execute(status -> {
            Long companyId = request.companyId();
            List<CompanyMatch> matches = List.of();
            if (companyId == null && companyHeard != null) {
                matches = matchCompanies(companyHeard);
                if (matches.size() == 1) {
                    companyId = matches.getFirst().id();
                }
            }
            Long managerId = companyId == null || managerHeard == null ? null : matchContact(companyId, managerHeard);
            return new Resolved(companyId, matches.size() > 1 ? matches : List.of(), managerId);
        });

        Integer baseMin = salary(result.text("baseMin"));
        Integer baseMax = salary(result.text("baseMax"));
        if (baseMin != null && baseMax != null && baseMax < baseMin) {
            baseMax = null;
        }
        SearchRequest search = new SearchRequest(
                resolved.companyId(),
                resolved.managerId(),
                title,
                AiText.choice(SearchEnums.Track.class, result.text("track")),
                null,
                AiText.choice(SearchEnums.EngagementType.class, result.text("engagementType")),
                percent(result.text("feePercent")),
                baseMin,
                baseMax,
                AiText.fit(result.text("location"), 255),
                AiText.choice(SearchEnums.WorkModel.class, result.text("workModel")),
                AiText.choice(SearchEnums.Timeline.class, result.text("timeline")),
                null,
                AiText.fit(result.text("productionOutcome"), 2_000),
                AiText.fit(result.text("technicalScope"), 2_000),
                AiText.fit(result.text("accountability"), 2_000),
                AiText.fit(result.text("regulatoryConstraints"), 2_000),
                AiText.fit(result.text("notes"), 2_000),
                AiText.fit(result.text("jobDescription"), SearchRequest.JOB_DESCRIPTION_MAX),
                null);
        return new IntakeDraft(search, knownCompany != null ? knownCompany : companyHeard, resolved.matches(),
                resolved.managerId() == null ? managerHeard : null, result.provider().label(), result.costCents());
    }

    /** Companies whose name matches loosely: "Ledgerlight AI, Inc." finds "Ledgerlight". */
    private List<CompanyMatch> matchCompanies(String name) {
        Set<String> wanted = CompanyNames.keys(name);
        if (wanted.isEmpty()) {
            return List.of();
        }
        return companies.findAll().stream()
                .filter(company -> !Collections.disjoint(wanted, CompanyNames.keys(company.getName())))
                .limit(MAX_MATCHES)
                .map(company -> new CompanyMatch(company.getId(), company.getName()))
                .toList();
    }

    /** The company's contact with that name, ignoring case and accents, when there's exactly one. */
    private Long matchContact(Long companyId, String name) {
        String wanted = personKey(name);
        List<Contact> found = contacts.findByCompanyId(companyId).stream()
                .filter(contact -> wanted.equals(personKey(contact.getName())))
                .toList();
        return found.size() == 1 ? found.getFirst().getId() : null;
    }

    static String personKey(String name) {
        return java.text.Normalizer.normalize(name == null ? "" : name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L} ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** "$180,000", "180k" or "180000" as 180000; null for anything that isn't a plausible base salary. */
    static Integer salary(String answer) {
        if (answer == null) {
            return null;
        }
        String value = answer.trim().toLowerCase(Locale.ROOT).replaceAll("[$,\\s]|usd", "");
        int multiplier = 1;
        if (value.endsWith("k")) {
            multiplier = 1_000;
            value = value.substring(0, value.length() - 1);
        }
        try {
            double amount = Double.parseDouble(value) * multiplier;
            return amount >= 10_000 && amount <= 5_000_000 ? (int) Math.round(amount) : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** "20" or "20%" as 20; null outside 0 to 100. */
    static BigDecimal percent(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(answer.trim().replace("%", "").trim());
            return value.signum() > 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0 ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
