package com.janemichael.jmrecruitingcrm.export;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /** include=contacts,companies for some lists; everything without it. */
    @GetMapping
    public ExportData export(@RequestParam(required = false) Set<String> include) {
        return exportService.export(include);
    }
}
