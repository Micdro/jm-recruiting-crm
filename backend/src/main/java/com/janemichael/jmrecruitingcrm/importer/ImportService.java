package com.janemichael.jmrecruitingcrm.importer;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.candidate.CandidateEnums;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.common.ActionType;
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
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactImportRequest;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ContactRow;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.DuplicateMode;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.ImportResult;
import com.janemichael.jmrecruitingcrm.importer.ImportDtos.RowError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Imports contacts (for example the ClickUp buyer list) and candidates from mapped CSV rows.
 * Row numbers in errors match the spreadsheet, where the header is row 1.
 */
@Service
public class ImportService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int FIRST_DATA_ROW = 2;

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final CandidateRepository candidateRepository;
    private final CurrentUserService currentUserService;

    public ImportService(
            CompanyRepository companyRepository,
            ContactRepository contactRepository,
            CandidateRepository candidateRepository,
            CurrentUserService currentUserService
    ) {
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.candidateRepository = candidateRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public ImportResult importContacts(ContactImportRequest request) {
        AppUser user = currentUserService.get();
        DuplicateMode mode = request.duplicateMode() == null ? DuplicateMode.SKIP : request.duplicateMode();

        Map<String, Company> companiesByName = new HashMap<>();
        for (Company company : companyRepository.findAll()) {
            companiesByName.putIfAbsent(company.getName().toLowerCase(), company);
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

            Company company = companiesByName.get(companyName.toLowerCase());
            if (company == null) {
                company = new Company();
                company.setName(companyName);
                company.setWebsite(Text.clean(row.companyWebsite()));
                company.setLocation(Text.clean(row.companyLocation()));
                company.setStatus(CompanyStatus.TARGET);
                company.setOwner(user);
                company = companyRepository.save(company);
                companiesByName.put(companyName.toLowerCase(), company);
                counts.companiesCreated++;
            }

            Contact existing = index.find(Text.clean(row.email()), row.linkedinUrl(), name, company.getId());
            if (existing != null) {
                if (mode == DuplicateMode.SKIP) {
                    counts.skipped++;
                    continue;
                }
                fillContact(existing, row, name, company, true);
                index.add(existing);
                counts.updated++;
                continue;
            }

            Contact contact = new Contact();
            contact.setOwner(user);
            fillContact(contact, row, name, company, false);
            index.add(contactRepository.save(contact));
            counts.created++;
        }

        return counts.result();
    }

    @Transactional
    public ImportResult importCandidates(CandidateImportRequest request) {
        AppUser user = currentUserService.get();
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

            Candidate existing = index.find(Text.clean(row.email()), row.linkedinUrl(), name, row.currentCompany());
            if (existing != null) {
                if (mode == DuplicateMode.SKIP) {
                    counts.skipped++;
                    continue;
                }
                fillCandidate(existing, row, name, true);
                index.add(existing);
                counts.updated++;
                continue;
            }

            Candidate candidate = new Candidate();
            candidate.setOwner(user);
            fillCandidate(candidate, row, name, false);
            index.add(candidateRepository.save(candidate));
            counts.created++;
        }

        return counts.result();
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
                check("Notes", row.notes(), 2000)
        );
    }

    private static void fillContact(Contact contact, ContactRow row, String name, Company company, boolean merge) {
        contact.setName(name);
        contact.setCompany(company);
        setIfPresent(Text.clean(row.title()), contact::setTitle, merge);
        setIfPresent(Text.clean(row.email()), contact::setEmail, merge);
        setIfPresent(Text.clean(row.phone()), contact::setPhone, merge);
        setIfPresent(Text.clean(row.linkedinUrl()), contact::setLinkedinUrl, merge);
        contact.setNotes(mergeNotes(contact.getNotes(), row.notes()));

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
                    : row.nextActionType() == null ? ActionType.EMAIL : row.nextActionType());
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
                check("Production evidence", row.productionEvidence(), 4000),
                check("Notes", row.notes(), 2000)
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
        candidate.setNotes(mergeNotes(candidate.getNotes(), row.notes()));

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
                    : row.nextActionType() == null ? ActionType.EMAIL : row.nextActionType());
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

    private static String mergeNotes(String existing, String incoming) {
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
        String merged = existing + "\n" + note;
        return merged.length() > 2000 ? merged.substring(0, 2000) : merged;
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter, boolean merge) {
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
            return name.toLowerCase() + "|" + companyId;
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
            return name.toLowerCase() + "|" + (companyKey == null ? "" : companyKey);
        }
    }
}
