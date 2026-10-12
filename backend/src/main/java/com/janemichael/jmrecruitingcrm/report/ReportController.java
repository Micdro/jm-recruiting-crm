package com.janemichael.jmrecruitingcrm.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** scope=mine (default) for the signed-in person's numbers; scope=team for everyone's. */
    @GetMapping("/weekly")
    public WeeklyReport weekly(@RequestParam(defaultValue = "mine") String scope) {
        return reportService.weekly("team".equalsIgnoreCase(scope));
    }
}
