package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.activity.LogActivityRequest;
import com.janemichael.jmrecruitingcrm.common.Later;
import com.janemichael.jmrecruitingcrm.search.SearchEnums;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final CandidateService candidateService;

    public CandidateController(CandidateService candidateService) {
        this.candidateService = candidateService;
    }

    @GetMapping
    public List<CandidateResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CandidateEnums.Status status,
            @RequestParam(required = false) SearchEnums.Track track,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(defaultValue = "false") boolean due
    ) {
        return candidateService.list(q, status, track, ownerId, due);
    }

    @GetMapping("/{id}")
    public CandidateResponse get(@PathVariable Long id) {
        return candidateService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CandidateResponse create(@Valid @RequestBody CandidateRequest request) {
        return candidateService.create(request);
    }

    @PutMapping("/{id}")
    public CandidateResponse update(@PathVariable Long id, @Valid @RequestBody CandidateRequest request) {
        return candidateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        candidateService.delete(id);
    }

    @PostMapping("/{id}/activities")
    public CandidateResponse logActivity(@PathVariable Long id, @Valid @RequestBody LogActivityRequest request) {
        return candidateService.logActivity(id, request);
    }

    /** Moves the next follow-up to the next business day or a week out. */
    @PostMapping("/{id}/snooze")
    public CandidateResponse snooze(@PathVariable Long id, @Valid @RequestBody Later.Request request) {
        return candidateService.snooze(id, request.until());
    }

    /** Undoes your last follow-up or snooze on this candidate from the last few minutes. */
    @PostMapping("/{id}/undo")
    public CandidateResponse undo(@PathVariable Long id) {
        return candidateService.undo(id);
    }
}
