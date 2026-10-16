package com.janemichael.jmrecruitingcrm.merge;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merge")
public class MergeController {

    private final MergeService mergeService;

    public MergeController(MergeService mergeService) {
        this.mergeService = mergeService;
    }

    /** Merges a duplicate contact, company or candidate into the one kept. */
    @PostMapping
    public MergeService.Result merge(@Valid @RequestBody MergeService.Request request) {
        return mergeService.merge(request);
    }
}
