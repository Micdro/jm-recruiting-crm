package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.candidate.CallNotesReader.CallDraft;
import com.janemichael.jmrecruitingcrm.candidate.CallNotesReader.CallNotesRequest;
import com.janemichael.jmrecruitingcrm.candidate.ResumeReader.ResumeDraft;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * AI for candidates: a resume fills in the record, and notes from a call become a write-up, a next
 * step and changes to the record. Each returns a draft to check; only saving a call writes anything.
 */
@RestController
@RequestMapping("/api/candidates")
public class CandidateAiController {

    private final ResumeReader resumes;
    private final CallNotesReader callNotes;
    private final CandidateService candidates;

    public CandidateAiController(ResumeReader resumes, CallNotesReader callNotes, CandidateService candidates) {
        this.resumes = resumes;
        this.callNotes = callNotes;
        this.candidates = candidates;
    }

    /** A new candidate's record from their resume: the raw file, with its name in {@code name}. */
    @PostMapping("/read-resume")
    public ResumeDraft readResume(@RequestParam String name, HttpServletRequest request) throws IOException {
        return resumes.read(name, request.getInputStream().readNBytes(CandidateFileService.MAX_BYTES + 1));
    }

    /** What a resume already on the candidate says, to fill in what their record is missing. */
    @PostMapping("/{id}/files/{fileId}/read")
    public ResumeDraft readFile(@PathVariable Long id, @PathVariable Long fileId) {
        return resumes.read(id, fileId);
    }

    @PostMapping("/{id}/call-notes/read")
    public CallDraft readCallNotes(@PathVariable Long id, @Valid @RequestBody CallNotesRequest request) {
        return callNotes.read(id, request);
    }

    @PostMapping("/{id}/call-notes")
    public CandidateResponse saveCall(@PathVariable Long id, @Valid @RequestBody CallUpdate update) {
        return candidates.saveCall(id, update);
    }
}
