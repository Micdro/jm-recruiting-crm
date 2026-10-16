package com.janemichael.jmrecruitingcrm.prospects;

import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.FoundCompany;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.KeyRequest;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.LimitsRequest;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.Reviewed;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.SettingsRequest;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.Status;
import com.janemichael.jmrecruitingcrm.prospects.ProspectDtos.Summary;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The AI Found page, its settings, and the card on Today. */
@RestController
@RequestMapping("/api/prospects")
public class ProspectController {

    private final ProspectFinder finder;

    public ProspectController(ProspectFinder finder) {
        this.finder = finder;
    }

    @GetMapping
    public Status status() {
        return finder.status();
    }

    @GetMapping("/summary")
    public Summary summary() {
        return finder.summary();
    }

    @GetMapping("/companies")
    public List<FoundCompany> companies() {
        return finder.found();
    }

    /** Starts a search now. New companies appear when it's done, usually within a few minutes. */
    @PostMapping("/run")
    public Status run() {
        return finder.runNow();
    }

    @PutMapping("/key")
    public Status saveKey(@Valid @RequestBody KeyRequest request) {
        return finder.saveKey(request);
    }

    @DeleteMapping("/key")
    public Status removeKey() {
        return finder.removeKey();
    }

    @PutMapping("/settings")
    public Status saveSettings(@Valid @RequestBody SettingsRequest request) {
        return finder.saveSettings(request);
    }

    @PutMapping("/limits")
    public Status saveLimits(@Valid @RequestBody LimitsRequest request) {
        return finder.saveLimits(request);
    }

    @PostMapping("/run-here")
    public Status runHere() {
        return finder.runHere();
    }

    @PostMapping("/companies/{id}/accept")
    public Reviewed accept(@PathVariable Long id) {
        return finder.accept(id);
    }

    @PostMapping("/companies/{id}/dismiss")
    public Reviewed dismiss(@PathVariable Long id) {
        return finder.dismiss(id);
    }
}
