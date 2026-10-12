package com.janemichael.jmrecruitingcrm.export;

import com.janemichael.jmrecruitingcrm.activity.ActivityResponse;
import com.janemichael.jmrecruitingcrm.candidate.CandidateResponse;
import com.janemichael.jmrecruitingcrm.company.CompanyResponse;
import com.janemichael.jmrecruitingcrm.contact.ContactResponse;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.PipelineEntryResponse;
import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.PlacementResponse;
import com.janemichael.jmrecruitingcrm.search.SearchResponse;

import java.util.List;

/** Full records, notes included, for the browser to write into an Excel file. Lists not asked for are null. */
public record ExportData(
        List<CompanyResponse> companies,
        List<ContactResponse> contacts,
        List<SearchResponse> searches,
        List<CandidateResponse> candidates,
        List<PipelineEntryResponse> pipeline,
        List<PlacementResponse> placements,
        List<ActivityResponse> activities
) {
}
