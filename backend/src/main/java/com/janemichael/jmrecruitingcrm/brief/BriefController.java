package com.janemichael.jmrecruitingcrm.brief;

import com.janemichael.jmrecruitingcrm.brief.BriefDtos.BriefResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Monday brief on Today: the week's facts, and the AI write-up of them. */
@RestController
@RequestMapping("/api/brief")
public class BriefController {

    private final BriefWriter brief;

    public BriefController(BriefWriter brief) {
        this.brief = brief;
    }

    @GetMapping
    public BriefResponse get() {
        return brief.get();
    }

    /** Writes this week's brief if it isn't yet; {@code rewrite=true} writes it again from today's facts. */
    @PostMapping("/write")
    public BriefResponse write(@RequestParam(defaultValue = "false") boolean rewrite) {
        return brief.write(rewrite);
    }
}
