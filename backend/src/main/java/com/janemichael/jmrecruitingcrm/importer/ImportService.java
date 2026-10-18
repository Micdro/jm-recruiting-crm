package com.janemichael.jmrecruitingcrm.importer;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.candidate.CandidateEnums;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.EnumCsv;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyStatus;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.ContactService;
import com.janemichael.jmrecruitingcrm.contact.OutreachStatus;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.CandidateImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.CandidateRow;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.CompanyImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.CompanyRow;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactRow;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.DuplicateMode;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ImportResult;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.RowError;
import com.janemichael.jmrecruitingcrm.undo.UndoLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Imports companies (the ClickUp client list), contacts (the outbound pipeline and buyer list)
 * and candidates from mapped CSV rows. Row numbers in errors match the spreadsheet, where the
 * header is row 1.
 */
@Service
public class ImportService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int FIRST_DATA_ROW = 2;
    static final int COMPANY_NOTES_MAX = 20000;
    static final int PERSON_NOTES_MAX = 10000;

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final CandidateRepository candidateRepository;
    private final AppUserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final UndoLog undoLog;

    public ImportService(
            CompanyRepository companyRepository,
            ContactRepository contactRepository,
            CandidateRepository candidateRepository,
            AppUserRepository userRepository,
            CurrentUserService currentUserService,
            UndoLog undoLog
    ) {
        this.undoLog = undoLog;
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.candidateRepository = candidateRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public ImportResult importCompanies(CompanyImportRequest request) {
        AppUser user = currentUserService.get();
        OwnerMatcher owners = new OwnerMatcher(userRepository.findAllByOrderByDisplayNameAsc());
        DuplicateMode mode = request.duplicateMode() == null ? DuplicateMode.SKIP : request.duplicateMode();

        Map<String, Company> companiesByName = new HashMap<>();
        for (Company company : companyRepository.findAll()) {
            companiesByName.putIfAbsent(company.getName().toLowerCase(Locale.ROOT), company);
        }

        Counts counts = new Counts();
        List<CompanyRow> rows = request.rows();

        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + FIRST_DATA_ROW;
            CompanyRow row = rows.get(i);
            if (row == null) {
                counts.errors.add(new RowError(rowNumber, "Row is empty"));
                continue;
            }

            String name = Text.clean(row.name());
            String error = validateCompany(row, name);
            if (error != null) {
                counts.errors.add(new RowError(rowNumber, error));
                continue;
            }

            AppUser owner = owners.match(row.owner(), user);
            Company existing = companiesByName.get(name.toLowerCase(Locale.ROOT));
            if (existing != null) {
                if (mode == DuplicateMode.SKIP) {
                    counts.skipped++;
                    continue;
                }
                fillCompany(existing, row, name, true);
                if (Text.clean(row.owner()) != null) {
                    existing.setOwner(owner);
                }
                counts.updated++;
                continue;
            }

            Company company = new Company();
            company.setOwner(owner);
            fillCompany(company, row, name, false);
            companiesByName.put(name.toLowerCase(Locale.ROOT), companyRepository.save(company));
            counts.created++;
        }

        return recorded(counts, "company", "companies");
    }

    @Transactional
    public ImportResult importContacts(ContactImportRequest request) {
        AppUser user = currentUserService.get();
        OwnerMatcher owners = new OwnerMatcher(userRepository.findAllByOrderByDisplayNameAsc());
        DuplicateMode mode = request.duplicateMode() == null ? DuplicateMode.SKIP : request.duplicateMode();

        Map<String, Company> companiesByName = new HashMap<>();
        for (Company company : companyRepository.findAll()) {
            companiesByName.putIfAbsent(company.getName().toLowerCase(Locale.ROOT), company);
        }

        ContactIndex index = new ContactIndex();
        contactRepository.findAllWithDetails().forEach(index::add);

        Counts counts = new Counts();
        List<ContactRow> rows = request.rows();

        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + FIRST_DATA_ROW;
            ContactRow row = rows.get(i);
            if (row == null) {
                counts.errors.add(new RowError(rowNumber, "Row is empty"));
                continue;
            }

            String name = fullName(row.name(), row.firstName(), row.lastName());
            String companyName = Text.clean(row.companyName());
            String error = validateContact(row, name, companyName);
            if (error != null) {
                counts.errors.add(new RowError(rowNumber, error));
                continue;
            }

            AppUser owner = owners.match(row.owner(), user);
            Company company = companiesByName.get(companyName.toLowerCase(Locale.ROOT));
            if (company == null) {
                company = new Company();
                company.setName(companyName);
                company.setWebsite(Text.clean(row.companyWebsite()));
                company.setLocation(Text.clean(row.companyLocation()));
                company.setStatus(CompanyStatus.LEAD);
                company.setOwner(owner);
                company = companyRepository.save(company);
                companiesByName.put(companyName.toLowerCase(Locale.ROOT), company);
                counts.companiesCreated++;
            }

            Contact existing = index.find(Text.clean(row.email()), row.linkedinUrl(), name, company.getId());
            if (existing != null) {
                if (mode == DuplicateMode.SKIP) {
                    counts.skipped++;
                    continue;
                }
                fillContact(existing, row, name, company, true);
                if (Text.clean(row.owner()) != null) {
                    existing.setOwner(owner);
                }
                index.add(existing);
                counts.updated++;
                continue;
            }

            Contact contact = new Contact();
            contact.setOwner(owner);
            fillContact(contact, row, name, company, false);
            index.add(contactRepository.save(contact));
            counts.created++;
        }

        return recorded(counts, "contact", "contacts");
    }

    @Transactional
    public ImportResult importCandidates(CandidateImportRequest request) {
        AppUser user = currentUserService.get();
        OwnerMatcher owners = new OwnerMatcher(userRepository.findAllByOrderByDisplayNameAsc());
        DuplicateMode mode = request.duplicateMode() == null ? DuplicateMode.SKIP : request.duplicateMode();

        CandidateIndex index = new CandidateIndex();
        candidateRepository.findAll().forEach(index::add);

        Counts counts = new Counts();
        List<CandidateRow> rows = request.rows();

        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + FIRST_DATA_ROW;
            CandidateRow row = rows.get(i);
            if (row == null) {
                counts.errors.add(new RowError(rowNumber, "Row is empty"));
                continue;
            }

            String name = fullName(row.name(), row.firstName(), row.lastName());
            String error = validateCandidate(row, name);
            if (error != null) {
                counts.errors.add(new RowError(rowNumber, error));
                continue;
            }

            AppUser owner = owners.match(row.owner(), user);
            Candidate existing = index.find(Text.clean(row.email()), row.linkedinUrl(), name, row.currentCompany());
            if (existing != null) {
                if (mode == DuplicateMode.SKIP) {
                    counts.skipped++;
                    continue;
                }
                fillCandidate(existing, row, name, true);
                if (Text.clean(row.owner()) != null) {
                    existing.setOwner(owner);
                }
                index.add(existing);
                counts.updated++;
                continue;
            }

            Candidate candidate = new Candidate();
            candidate.setOwner(owner);
            fillCandidate(candidate, row, name, false);
            index.add(candidateRepository.save(candidate));
            counts.created++;
        }

        return recorded(counts, "candidate", "candidates");
    }

    // Companies -------------------------------------------------------------

    private static String validateCompany(CompanyRow row, String name) {
        if (name == null) {
            return "Company name is missing";
        }
        if (row.anticipatedHires() != null && row.anticipatedHires() < 0) {
            return "Anticipated hires can't be negative";
        }
        return firstTooLong(
                check("Company name", name, 255),
                check("Website", row.website(), 500),
                check("Location", row.location(), 255),
                check("Company size", row.companySize(), 100),
                check("Focus", row.focus(), 500),
                check("LinkedIn profile", row.linkedinProfile(), 500),
                check("Notes", row.notes(), COMPANY_NOTES_MAX)
        );
    }

    private static void fillCompany(Company company, CompanyRow row, String name, boolean merge) {
        if (!merge) {
            company.setName(name);
        }
        setIfPresent(Text.clean(row.website()), company::setWebsite, merge);
        setIfPresent(Text.clean(row.location()), company::setLocation, merge);
        setIfPresent(Text.clean(row.companySize()), company::setCompanySize, merge);
        setIfPresent(Text.clean(row.focus()), company::setFocus, merge);
        setIfPresent(Text.clean(row.linkedinProfile()), company::setLinkedinProfile, merge);
        company.setNotes(mergeNotes(company.getNotes(), row.notes(), COMPANY_NOTES_MAX));

        if (row.status() != null) {
            company.setStatus(row.status());
        } else if (!merge) {
            company.setStatus(CompanyStatus.LEAD);
        }
        if (row.segments() != null && !row.segments().isEmpty() || !merge) {
            company.setSegments(EnumCsv.join(row.segments()));
        }
        setIfPresent(row.industry(), company::setIndustry, merge);
        setIfPresent(row.tier(), company::setTier, merge);
        setIfPresent(row.priority(), company::setPriority, merge);
        setIfPresent(row.hiringNeed(), company::setHiringNeed, merge);
        setIfPresent(row.hiringTimeline(), company::setHiringTimeline, merge);
        setIfPresent(row.anticipatedHires(), company::setAnticipatedHires, merge);
        setIfPresent(row.leadSource(), company::setLeadSource, merge);
        setIfPresent(row.lastContactedDate(), company::setLastContactedDate, merge);
        setIfPresent(row.followUpDate(), company::setFollowUpDate, merge);
    }

    // Contacts --------------------------------------------------------------

    private static String validateContact(ContactRow row, String name, String companyName) {
        if (name == null) {
            return "Name is missing";
        }
        if (companyName == null) {
            return "Company is missing";
        }
        String email = Text.clean(row.email());
        if (email != null && !EMAIL.matcher(email).matches()) {
            return "Email \"" + email + "\" isn't valid";
        }
        return firstTooLong(
                check("Name", name, 255),
                check("Company", companyName, 255),
                check("Company website", row.companyWebsite(), 500),
                check("Company location", row.companyLocation(), 255),
                check("Title", row.title(), 255),
                check("Email", row.email(), 255),
                check("Phone", row.phone(), 50),
                check("LinkedIn URL", row.linkedinUrl(), 500),
                check("Notes", row.notes(), PERSON_NOTES_MAX)
        );
    }

    private static void fillContact(Contact contact, ContactRow row, String name, Company company, boolean merge) {
        contact.setName(name);
        contact.setCompany(company);
        setIfPresent(Text.clean(row.title()), contact::setTitle, merge);
        setIfPresent(Text.clean(row.email()), contact::setEmail, merge);
        setIfPresent(Text.clean(row.phone()), contact::setPhone, merge);
        setIfPresent(Text.clean(row.linkedinUrl()), contact::setLinkedinUrl, merge);
        setIfPresent(row.buyingRole(), contact::setBuyingRole, merge);
        contact.setNotes(mergeNotes(contact.getNotes(), row.notes(), PERSON_NOTES_MAX));

        if (row.touchCount() != null) {
            contact.setTouchCount(Math.max(0, Math.min(ContactService.MAX_SEQUENCE_TOUCHES, row.touchCount())));
        }

        OutreachStatus status = row.status();
        if (status == null && !merge) {
            status = contact.getTouchCount() > 0 ? OutreachStatus.IN_SEQUENCE : OutreachStatus.NEW;
        }
        if (status != null) {
            if (status.isCold() && contact.getTouchCount() >= ContactService.MAX_SEQUENCE_TOUCHES) {
                status = OutreachStatus.NO_RESPONSE;
            } else if (status == OutreachStatus.NEW && contact.getTouchCount() > 0) {
                status = OutreachStatus.IN_SEQUENCE;
            }
            contact.setStatus(status);
        }

        if (row.lastContactedDate() != null || !merge) {
            contact.setLastContactedDate(row.lastContactedDate());
        }
        if (row.nextActionDate() != null || !merge) {
            contact.setNextActionDate(row.nextActionDate());
            contact.setNextActionType(row.nextActionDate() == null
                    ? null
                    : row.nextActionType() == null
                            ? ContactService.channelForTouch(contact.getTouchCount())
                            : row.nextActionType());
        }
    }

    // Candidates ------------------------------------------------------------

    private static String validateCandidate(CandidateRow row, String name) {
        if (name == null) {
            return "Name is missing";
        }
        String email = Text.clean(row.email());
        if (email != null && !EMAIL.matcher(email).matches()) {
            return "Email \"" + email + "\" isn't valid";
        }
        return firstTooLong(
                check("Name", name, 255),
                check("Email", row.email(), 255),
                check("Phone", row.phone(), 50),
                check("LinkedIn URL", row.linkedinUrl(), 500),
                check("Location", row.location(), 255),
                check("Current title", row.currentTitle(), 255),
                check("Current company", row.currentCompany(), 255),
                check("Compensation", row.compExpectation(), 255),
                check("Production evidence", row.productionEvidence(), PERSON_NOTES_MAX),
                check("Notes", row.notes(), PERSON_NOTES_MAX)
        );
    }

    private static void fillCandidate(Candidate candidate, CandidateRow row, String name, boolean merge) {
        candidate.setName(name);
        setIfPresent(Text.clean(row.email()), candidate::setEmail, merge);
        setIfPresent(Text.clean(row.phone()), candidate::setPhone, merge);
        setIfPresent(Text.clean(row.linkedinUrl()), candidate::setLinkedinUrl, merge);
        setIfPresent(Text.clean(row.location()), candidate::setLocation, merge);
        setIfPresent(Text.clean(row.currentTitle()), candidate::setCurrentTitle, merge);
        setIfPresent(Text.clean(row.currentCompany()), candidate::setCurrentCompany, merge);
        setIfPresent(Text.clean(row.compExpectation()), candidate::setCompExpectation, merge);
        setIfPresent(Text.clean(row.productionEvidence()), candidate::setProductionEvidence, merge);
        candidate.setNotes(mergeNotes(candidate.getNotes(), row.notes(), PERSON_NOTES_MAX));
        if (row.roleAreas() != null && !row.roleAreas().isEmpty() || !merge) {
            candidate.setRoleAreas(EnumCsv.join(row.roleAreas()));
        }
        setIfPresent(row.workPreference(), candidate::setWorkPreference, merge);
        setIfPresent(row.experience(), candidate::setExperience, merge);
        setIfPresent(row.employmentType(), candidate::setEmploymentType, merge);
        if (row.willingToRelocate() != null) {
            candidate.setWillingToRelocate(row.willingToRelocate());
        }

        if (row.track() != null || !merge) {
            candidate.setTrack(row.track());
        }
        if (row.source() != null || !merge) {
            candidate.setSource(row.source());
        }
        if (row.status() != null) {
            candidate.setStatus(row.status());
        } else if (!merge) {
            candidate.setStatus(CandidateEnums.Status.ACTIVE);
        }
        if (row.nextActionDate() != null || !merge) {
            candidate.setNextActionDate(row.nextActionDate());
            candidate.setNextActionType(row.nextActionDate() == null
                    ? null
                    : row.nextActionType() == null ? ActionType.LINKEDIN : row.nextActionType());
        }
    }

    // Shared ----------------------------------------------------------------

    static String fullName(String name, String firstName, String lastName) {
        String full = Text.clean(name);
        if (full != null) {
            return full;
        }
        String first = Text.clean(firstName);
        String last = Text.clean(lastName);
        if (first == null && last == null) {
            return null;
        }
        if (first == null) {
            return last;
        }
        return last == null ? first : first + " " + last;
    }

    static String mergeNotes(String existing, String incoming, int max) {
        String note = Text.clean(incoming);
        if (note == null) {
            return existing;
        }
        if (existing == null) {
            return note;
        }
        if (existing.contains(note)) {
            return existing;
        }
        String merged = existing + "\n\n" + note;
        return merged.length() > max ? merged.substring(0, max) : merged;
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter, boolean merge) {
        if (value != null || !merge) {
            setter.accept(value);
        }
    }

    private static String check(String label, String value, int max) {
        String cleaned = Text.clean(value);
        if (cleaned != null && cleaned.length() > max) {
            return label + " is longer than " + max + " characters";
        }
        return null;
    }

    private static String firstTooLong(String... problems) {
        for (String problem : problems) {
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /**
     * Matches the owner column to a team member by email, full name, first name or initials,
     * so ClickUp's "MD" and "DD" become Michael and Dimitrios.
     */
    static final class OwnerMatcher {
        private final Map<String, AppUser> byKey = new HashMap<>();

        OwnerMatcher(List<AppUser> users) {
            Map<String, Integer> firstNameCounts = new HashMap<>();
            Map<String, Integer> initialsCounts = new HashMap<>();
            for (AppUser user : users) {
                firstNameCounts.merge(firstName(user), 1, Integer::sum);
                initialsCounts.merge(initials(user), 1, Integer::sum);
            }
            for (AppUser user : users) {
                if (firstNameCounts.get(firstName(user)) == 1) {
                    byKey.putIfAbsent(firstName(user), user);
                }
                if (initialsCounts.get(initials(user)) == 1) {
                    byKey.putIfAbsent(initials(user), user);
                }
            }
            for (AppUser user : users) {
                byKey.put(normalize(user.getDisplayName()), user);
                byKey.put(normalize(user.getEmail()), user);
            }
        }

        AppUser match(String value, AppUser fallback) {
            String key = normalize(value);
            if (key.isEmpty()) {
                return fallback;
            }
            AppUser exact = byKey.get(key);
            if (exact != null) {
                return exact;
            }
            // ClickUp exports several assignees as "Michael Drougas, Jane ..."; the first one owns it.
            for (String part : key.split("[,;]")) {
                AppUser match = byKey.get(part.trim());
                if (match != null) {
                    return match;
                }
            }
            return fallback;
        }

        private static String firstName(AppUser user) {
            String[] words = normalize(user.getDisplayName()).split(" ");
            return words[0];
        }

        private static String initials(AppUser user) {
            StringBuilder initials = new StringBuilder();
            for (String word : normalize(user.getDisplayName()).split(" ")) {
                if (!word.isEmpty()) {
                    initials.append(word.charAt(0));
                }
            }
            return initials.toString();
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
    }

    /** Names the import in Recent changes, so a wrong file can be taken back in one go. */
    private ImportResult recorded(Counts counts, String one, String many) {
        if (counts.created > 0) {
            undoLog.record("Imported " + UndoLog.count(counts.created, one, many)
                    + (counts.updated > 0 ? " and updated " + counts.updated : ""), UndoLog.Kind.OTHER, null);
        } else if (counts.updated > 0) {
            undoLog.record("Updated " + UndoLog.count(counts.updated, one, many) + " from an import",
                    UndoLog.Kind.OTHER, null);
        }
        return counts.result();
    }

    private static final class Counts {
        int created;
        int updated;
        int skipped;
        int companiesCreated;
        final List<RowError> errors = new ArrayList<>();

        ImportResult result() {
            return new ImportResult(created, updated, skipped, companiesCreated, errors);
        }
    }

    /** Finds an existing contact by email, then LinkedIn, then name at the same company. */
    private static final class ContactIndex {
        final Map<String, Contact> byEmail = new HashMap<>();
        final Map<String, Contact> byLinkedin = new HashMap<>();
        final Map<String, Contact> byNameAndCompany = new HashMap<>();

        void add(Contact contact) {
            String email = Text.key(contact.getEmail());
            if (email != null) {
                byEmail.put(email, contact);
            }
            String linkedin = Text.linkedinKey(contact.getLinkedinUrl());
            if (linkedin != null) {
                byLinkedin.put(linkedin, contact);
            }
            byNameAndCompany.put(nameKey(contact.getName(), contact.getCompany().getId()), contact);
        }

        Contact find(String email, String linkedinUrl, String name, Long companyId) {
            String emailKey = Text.key(email);
            if (emailKey != null && byEmail.containsKey(emailKey)) {
                return byEmail.get(emailKey);
            }
            String linkedin = Text.linkedinKey(linkedinUrl);
            if (linkedin != null && byLinkedin.containsKey(linkedin)) {
                return byLinkedin.get(linkedin);
            }
            return byNameAndCompany.get(nameKey(name, companyId));
        }

        private static String nameKey(String name, Long companyId) {
            return name.toLowerCase(Locale.ROOT) + "|" + companyId;
        }
    }

    /** Finds an existing candidate by email, then LinkedIn, then name at the same current company. */
    private static final class CandidateIndex {
        final Map<String, Candidate> byEmail = new HashMap<>();
        final Map<String, Candidate> byLinkedin = new HashMap<>();
        final Map<String, Candidate> byNameAndCompany = new HashMap<>();

        void add(Candidate candidate) {
            String email = Text.key(candidate.getEmail());
            if (email != null) {
                byEmail.put(email, candidate);
            }
            String linkedin = Text.linkedinKey(candidate.getLinkedinUrl());
            if (linkedin != null) {
                byLinkedin.put(linkedin, candidate);
            }
            byNameAndCompany.put(nameKey(candidate.getName(), candidate.getCurrentCompany()), candidate);
        }

        Candidate find(String email, String linkedinUrl, String name, String currentCompany) {
            String emailKey = Text.key(email);
            if (emailKey != null && byEmail.containsKey(emailKey)) {
                return byEmail.get(emailKey);
            }
            String linkedin = Text.linkedinKey(linkedinUrl);
            if (linkedin != null && byLinkedin.containsKey(linkedin)) {
                return byLinkedin.get(linkedin);
            }
            return byNameAndCompany.get(nameKey(name, currentCompany));
        }

        private static String nameKey(String name, String company) {
            String companyKey = Text.key(company);
            return name.toLowerCase(Locale.ROOT) + "|" + (companyKey == null ? "" : companyKey);
        }
    }
}
