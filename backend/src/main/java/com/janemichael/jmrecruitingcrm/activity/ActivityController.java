package com.janemichael.jmrecruitingcrm.activity;

import com.janemichael.jmrecruitingcrm.activity.ActivityService.NoteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    public List<ActivityResponse> list(
            @RequestParam(required = false) Long contactId,
            @RequestParam(required = false) Long candidateId,
            @RequestParam(required = false) Long searchId,
            @RequestParam(required = false) Long companyId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return activityService.list(contactId, candidateId, searchId, companyId, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityResponse logNote(@Valid @RequestBody NoteRequest request) {
        return activityService.logNote(request);
    }
}
