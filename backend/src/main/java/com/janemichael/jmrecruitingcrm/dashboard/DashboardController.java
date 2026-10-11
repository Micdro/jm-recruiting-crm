package com.janemichael.jmrecruitingcrm.dashboard;

import com.janemichael.jmrecruitingcrm.dashboard.DashboardService.DashboardResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** scope=mine (default) shows the signed-in user's queue; scope=team shows everyone's. */
    @GetMapping
    public DashboardResponse get(@RequestParam(defaultValue = "mine") String scope) {
        return dashboardService.get("team".equalsIgnoreCase(scope));
    }
}
