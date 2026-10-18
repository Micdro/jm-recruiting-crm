package com.janemichael.jmrecruitingcrm.search;

import com.janemichael.jmrecruitingcrm.pipeline.RationaleWriter;
import com.janemichael.jmrecruitingcrm.pipeline.RationaleWriter.RationaleDraft;
import com.janemichael.jmrecruitingcrm.search.IntakeReader.IntakeDraft;
import com.janemichael.jmrecruitingcrm.search.IntakeReader.IntakeRequest;
import com.janemichael.jmrecruitingcrm.search.JobDescriptionWriter.JobDescriptionDraft;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI for searches: intake notes become a search and a job description, a search's brief becomes a
 * job description, and a candidate's record becomes the rationale the client reads. Each returns a
 * draft to check; only saving a job description writes anything.
 */
@RestController
@RequestMapping("/api")
public class SearchAiController {

    public record JobDescriptionRequest(
            @Size(max = SearchRequest.JOB_DESCRIPTION_MAX, message = "The job description must be 10,000 characters or fewer")
            String jobDescription
    ) {
    }

    private final IntakeReader intake;
    private final JobDescriptionWriter jobDescriptions;
    private final RationaleWriter rationales;
    private final SearchService searches;

    public SearchAiController(IntakeReader intake, JobDescriptionWriter jobDescriptions, RationaleWriter rationales,
                              SearchService searches) {
        this.intake = intake;
        this.jobDescriptions = jobDescriptions;
        this.rationales = rationales;
        this.searches = searches;
    }

    @PostMapping("/searches/read-intake")
    public IntakeDraft readIntake(@Valid @RequestBody IntakeRequest request) {
        return intake.read(request);
    }

    @PostMapping("/searches/{id}/job-description/draft")
    public JobDescriptionDraft draftJobDescription(@PathVariable Long id) {
        return jobDescriptions.draft(id);
    }

    @PutMapping("/searches/{id}/job-description")
    public SearchResponse saveJobDescription(@PathVariable Long id, @Valid @RequestBody JobDescriptionRequest request) {
        return searches.saveJobDescription(id, request.jobDescription());
    }

    @PostMapping("/pipeline/{id}/rationale/draft")
    public RationaleDraft draftRationale(@PathVariable Long id) {
        return rationales.draft(id);
    }
}
