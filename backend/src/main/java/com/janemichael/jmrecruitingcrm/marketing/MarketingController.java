package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.DraftRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.DraftResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostedRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.ResultsRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SuggestRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SuggestResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.WeekResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** The Marketing page: the week's posts, the ideas waiting, and AI drafts. */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

    private final MarketingService marketing;
    private final PostDrafter drafter;

    public MarketingController(MarketingService marketing, PostDrafter drafter) {
        this.marketing = marketing;
        this.drafter = drafter;
    }

    /** The week holding {@code date} (this week if left out), and the ideas without a date. */
    @GetMapping("/week")
    public WeekResponse week(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate date) {
        return marketing.week(date);
    }

    @GetMapping("/posts")
    public List<PostResponse> list() {
        return marketing.list();
    }

    @GetMapping("/posts/{id}")
    public PostResponse get(@PathVariable Long id) {
        return marketing.get(id);
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse create(@Valid @RequestBody PostRequest request) {
        return marketing.create(request);
    }

    @PutMapping("/posts/{id}")
    public PostResponse update(@PathVariable Long id, @Valid @RequestBody PostRequest request) {
        return marketing.update(id, request);
    }

    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        marketing.delete(id);
    }

    @PostMapping("/posts/{id}/posted")
    public PostResponse markPosted(@PathVariable Long id, @Valid @RequestBody(required = false) PostedRequest request) {
        return marketing.markPosted(id, request == null ? new PostedRequest(null, null) : request);
    }

    @PutMapping("/posts/{id}/results")
    public PostResponse saveResults(@PathVariable Long id, @Valid @RequestBody ResultsRequest request) {
        return marketing.saveResults(id, request);
    }

    /** Drafts the post with the AI the account owner chose. The draft isn't saved. */
    @PostMapping("/posts/{id}/draft")
    public DraftResponse draft(@PathVariable Long id, @Valid @RequestBody(required = false) DraftRequest request) {
        return drafter.draft(id, request);
    }

    /** Suggests post ideas. They aren't saved; the person adds the ones they like. */
    @PostMapping("/ideas/suggest")
    public SuggestResponse suggest(@Valid @RequestBody SuggestRequest request) {
        return drafter.suggest(request);
    }
}
