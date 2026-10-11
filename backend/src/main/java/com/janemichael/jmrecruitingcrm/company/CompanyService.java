package com.janemichael.jmrecruitingcrm.company;

import com.janemichael.jmrecruitingcrm.activity.ActivityLogger;
import com.janemichael.jmrecruitingcrm.activity.ActivityType;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.Labels;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class CompanyService {

    public static final String HAS_CONTACTS_MESSAGE =
            "Company has contacts. Delete or reassign them before deleting the company.";
    public static final String HAS_SEARCHES_MESSAGE =
            "Company has searches. Delete them before deleting the company.";

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final SearchRepository searchRepository;
    private final CurrentUserService currentUserService;
    private final ActivityLogger activityLogger;

    public CompanyService(
            CompanyRepository companyRepository,
            ContactRepository contactRepository,
            SearchRepository searchRepository,
            CurrentUserService currentUserService,
            ActivityLogger activityLogger
    ) {
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.searchRepository = searchRepository;
        this.currentUserService = currentUserService;
        this.activityLogger = activityLogger;
    }

    public List<CompanyResponse> list(String q, CompanyStatus status) {
        String query = Text.key(q);
        Map<Long, Long> contactCounts = contactCounts();
        Map<Long, Long> openSearchCounts = openSearchCounts();

        return companyRepository.findAllWithOwner()
                .stream()
                .filter(company -> status == null || company.getStatus() == status)
                .filter(company -> query == null || matches(company, query))
                .map(company -> CompanyResponse.from(company,
                        contactCounts.getOrDefault(company.getId(), 0L),
                        openSearchCounts.getOrDefault(company.getId(), 0L)))
                .toList();
    }

    public CompanyResponse get(Long id) {
        Company company = find(id);
        return CompanyResponse.from(company,
                contactCounts().getOrDefault(id, 0L),
                openSearchCounts().getOrDefault(id, 0L));
    }

    @Transactional
    public CompanyResponse create(CompanyRequest request) {
        AppUser user = currentUserService.get();
        String name = request.name().trim();

        if (companyRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A company with this name already exists");
        }

        Company company = new Company();
        apply(request, company);
        company.setStatus(request.status() == null ? CompanyStatus.TARGET : request.status());
        company.setOwner(request.ownerId() == null ? user : currentUserService.findOwner(request.ownerId()));

        return CompanyResponse.from(companyRepository.save(company), 0L, 0L);
    }

    @Transactional
    public CompanyResponse update(Long id, CompanyRequest request) {
        Company company = find(id);
        String name = request.name().trim();

        if (companyRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("A company with this name already exists");
        }

        CompanyStatus previousStatus = company.getStatus();

        apply(request, company);
        if (request.status() != null) {
            company.setStatus(request.status());
        }
        company.setOwner(currentUserService.findOwner(request.ownerId()));

        if (previousStatus != company.getStatus()) {
            activityLogger.forCompany(company, ActivityType.STATUS_CHANGE,
                    "Status changed from " + Labels.of(previousStatus) + " to " + Labels.of(company.getStatus()),
                    currentUserService.get());
        }

        return CompanyResponse.from(company,
                contactCounts().getOrDefault(id, 0L),
                openSearchCounts().getOrDefault(id, 0L));
    }

    @Transactional
    public void delete(Long id) {
        if (contactRepository.existsByCompanyId(id)) {
            throw new ConflictException(HAS_CONTACTS_MESSAGE);
        }
        if (searchRepository.existsByCompanyId(id)) {
            throw new ConflictException(HAS_SEARCHES_MESSAGE);
        }
        if (companyRepository.deleteCompanyById(id) == 0) {
            throw new NotFoundException("Company not found");
        }
    }

    private Map<Long, Long> contactCounts() {
        return toCountMap(contactRepository.countByCompany());
    }

    private Map<Long, Long> openSearchCounts() {
        List<SearchEnums.Status> openStatuses = Arrays.stream(SearchEnums.Status.values())
                .filter(SearchEnums.Status::isOpen)
                .toList();
        return toCountMap(searchRepository.countByCompanyAndStatusIn(openStatuses));
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static boolean matches(Company company, String query) {
        return contains(company.getName(), query)
                || contains(company.getFocus(), query)
                || contains(company.getLocation(), query)
                || contains(company.getWebsite(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private static void apply(CompanyRequest request, Company company) {
        company.setName(request.name().trim());
        company.setWebsite(Text.clean(request.website()));
        company.setLocation(Text.clean(request.location()));
        company.setCompanySize(Text.clean(request.companySize()));
        company.setFocus(Text.clean(request.focus()));
        company.setLinkedinProfile(Text.clean(request.linkedinProfile()));
        company.setNotes(Text.clean(request.notes()));
    }

    private Company find(Long id) {
        return companyRepository.findWithOwnerById(id)
                .orElseThrow(() -> new NotFoundException("Company not found"));
    }
}
