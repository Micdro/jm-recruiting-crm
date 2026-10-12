package com.janemichael.jmrecruitingcrm.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.Map;

/**
 * Public health check for the hosting platform. Also says when this copy started, so the desktop
 * app's page can tell when it has restarted after an update.
 */
@RestController
public class HealthController {

    private final long started = ManagementFactory.getRuntimeMXBean().getStartTime();

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "started", started);
    }
}
