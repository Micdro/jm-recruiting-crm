package com.janemichael.jmrecruitingcrm.export;

import com.janemichael.jmrecruitingcrm.activity.ActivityRepository;
import com.janemichael.jmrecruitingcrm.activity.ActivityResponse;
import com.janemichael.jmrecruitingcrm.candidate.CandidateRepository;
import com.janemichael.jmrecruitingcrm.candidate.CandidateResponse;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.company.CompanyRepository;
import com.janemichael.jmrecruitingcrm.company.CompanyResponse;
import com.janemichael.jmrecruitingcrm.contact.ContactRepository;
import com.janemichael.jmrecruitingcrm.contact.ContactResponse;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.PipelineEntryResponse;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineRepository;
import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.PlacementResponse;
import com.janemichael.jmrecruitingcrm.placement.PlacementRepository;
import com.janemichael.jmrecruitingcrm.search.SearchRepository;
import com.janemichael.jmrecruitingcrm.search.SearchResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything (or the parts asked for) in full, for an Excel export. */
@Service
@Transactional(readOnly = true)
public class ExportService {

    public static final List<String> PARTS =
            List.of("companies", "contacts", "searches", "candidates", "pipeline", "placements", "activities");

    private final CompanyRepository companies;
    private final ContactRepository contacts;
    private final SearchRepository searches;
    private final CandidateRepository candidates;
    private final PipelineRepository pipeline;
    private final PlacementRepository placements;
    private final ActivityRepository activities;

    public ExportService(
            CompanyRepository companies,
            ContactRepository contacts,
            SearchRepository searches,
            CandidateRepository candidates,
            PipelineRepository pipeline,
            PlacementRepository placements,
            ActivityRepository activities
    ) {
        this.companies = companies;
        this.contacts = contacts;
        this.searches = searches;
        this.candidates = candidates;
        this.pipeline = pipeline;
        this.placements = placements;
        this.activities = activities;
    }

    /** @param include which lists to send (see {@link #PARTS}); all of them when empty */
    public ExportData export(Set<String> include) {
        Set<String> parts = include == null || include.isEmpty() ? new HashSet<>(PARTS) : include;
        for (String part : parts) {
            if (!PARTS.contains(part)) {
                throw new BadRequestException("Unknown list to export: " + part);
            }
        }

        List<CompanyResponse> companyRows = null;
        if (parts.contains("companies")) {
            Map<Long, Long> contactCounts = new HashMap<>();
            for (Object[] row : contacts.countByCompany()) {
                contactCounts.put((Long) row[0], (Long) row[1]);
            }
            companyRows = companies.findAllWithOwner().stream()
                    .map(company -> CompanyResponse.from(company, contactCounts.getOrDefault(company.getId(), 0L), 0L, null))
                    .toList();
        }
        return new ExportData(
                companyRows,
                parts.contains("contacts") ? contacts.findAllWithDetails().stream().map(ContactResponse::from).toList() : null,
                parts.contains("searches")
                        ? searches.findAllWithDetails().stream().map(search -> SearchResponse.from(search, null)).toList()
                        : null,
                parts.contains("candidates")
                        ? candidates.findAllWithOwner().stream().map(candidate -> CandidateResponse.from(candidate, 0L)).toList()
                        : null,
                parts.contains("pipeline") ? pipelineRows() : null,
                parts.contains("placements")
                        ? placements.findAllWithDetails().stream().map(PlacementResponse::from).toList()
                        : null,
                parts.contains("activities")
                        ? activities.findAllWithDetails().stream().map(ActivityResponse::from).toList()
                        : null
        );
    }

    private List<PipelineEntryResponse> pipelineRows() {
        Set<String> placed = new HashSet<>();
        placements.findAllWithDetails().forEach(placement ->
                placed.add(placement.getSearch().getId() + ":" + placement.getCandidate().getId()));
        return pipeline.findAllWithDetails().stream()
                .map(entry -> PipelineEntryResponse.from(entry,
                        placed.contains(entry.getSearch().getId() + ":" + entry.getCandidate().getId())))
                .toList();
    }
}
