package com.janemichael.jmrecruitingcrm.pipeline;

import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.AddToSearchRequest;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.DebriefRequest;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.InterviewRequest;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.PipelineEntryResponse;
import com.janemichael.jmrecruitingcrm.pipeline.PipelineDtos.UpdateEntryRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping("/searches/{searchId}/pipeline")
    public List<PipelineEntryResponse> forSearch(@PathVariable Long searchId) {
        return pipelineService.forSearch(searchId);
    }

    @GetMapping("/candidates/{candidateId}/pipeline")
    public List<PipelineEntryResponse> forCandidate(@PathVariable Long candidateId) {
        return pipelineService.forCandidate(candidateId);
    }

    @PostMapping("/pipeline")
    @ResponseStatus(HttpStatus.CREATED)
    public PipelineEntryResponse add(@Valid @RequestBody AddToSearchRequest request) {
        return pipelineService.add(request);
    }

    @PutMapping("/pipeline/{id}")
    public PipelineEntryResponse update(@PathVariable Long id, @Valid @RequestBody UpdateEntryRequest request) {
        return pipelineService.update(id, request);
    }

    @DeleteMapping("/pipeline/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        pipelineService.delete(id);
    }

    /** Schedules or moves the next interview for this candidate in this search. */
    @PutMapping("/pipeline/{id}/interview")
    public PipelineEntryResponse scheduleInterview(@PathVariable Long id, @Valid @RequestBody InterviewRequest request) {
        return pipelineService.scheduleInterview(id, request);
    }

    @DeleteMapping("/pipeline/{id}/interview")
    public PipelineEntryResponse cancelInterview(@PathVariable Long id) {
        return pipelineService.cancelInterview(id);
    }

    /** Records the debrief with the candidate or the client after the interview. */
    @PostMapping("/pipeline/{id}/debrief")
    public PipelineEntryResponse debrief(@PathVariable Long id, @Valid @RequestBody DebriefRequest request) {
        return pipelineService.debrief(id, request);
    }
}
