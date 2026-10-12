package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.candidate.CandidateFileService.CandidateFileResponse;
import com.janemichael.jmrecruitingcrm.candidate.CandidateFileService.Download;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Resumes and other files on a candidate. Uploads are the raw file, with its name in {@code name}. */
@RestController
@RequestMapping("/api/candidates/{candidateId}/files")
public class CandidateFileController {

    private final CandidateFileService files;

    public CandidateFileController(CandidateFileService files) {
        this.files = files;
    }

    @GetMapping
    public List<CandidateFileResponse> list(@PathVariable Long candidateId) {
        return files.list(candidateId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CandidateFileResponse upload(@PathVariable Long candidateId, @RequestParam String name,
                                        HttpServletRequest request) throws IOException {
        return files.upload(candidateId, name, request.getInputStream());
    }

    /** The file, shown in the browser (PDF, text) or downloaded; download=true always downloads. */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> content(@PathVariable Long candidateId, @PathVariable Long fileId,
                                          @RequestParam(defaultValue = "false") boolean download) {
        Download file = files.download(candidateId, fileId);
        ContentDisposition disposition = (file.inline() && !download ? ContentDisposition.inline()
                : ContentDisposition.attachment()).filename(file.fileName(), StandardCharsets.UTF_8).build();
        MediaType type = MediaType.parseMediaType(file.contentType());
        if ("text".equals(type.getType())) {
            type = new MediaType(type, StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(type)
                .contentLength(file.content().length)
                .body(file.content());
    }

    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long candidateId, @PathVariable Long fileId) {
        files.delete(candidateId, fileId);
    }
}
