package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.DraftRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.DraftResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PictureRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PictureResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PlanRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PlanResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostTemplateRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostTemplateResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostedRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.ResultsRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SetupRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SetupResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SuggestRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SuggestResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.WeekResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/** The Marketing page: the week's posts, the ideas waiting, AI drafts and the post templates. */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

    private final MarketingService marketing;
    private final PostDrafter drafter;
    private final WeekPlanner planner;
    private final PostPictures pictures;
    private final PostPublisher publisher;
    private final PostTemplateService templates;

    public MarketingController(MarketingService marketing, PostDrafter drafter, WeekPlanner planner,
                               PostPictures pictures, PostPublisher publisher, PostTemplateService templates) {
        this.marketing = marketing;
        this.drafter = drafter;
        this.planner = planner;
        this.pictures = pictures;
        this.publisher = publisher;
        this.templates = templates;
    }

    /** The week holding {@code date} (this week if left out), and the ideas without a date. */
    @GetMapping("/week")
    public WeekResponse week(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate date) {
        return marketing.week(date);
    }

    /** Fills the week's open posting days with AI drafts, from the plan. */
    @PostMapping("/week/setup")
    public SetupResponse setUpWeek(@Valid @RequestBody(required = false) SetupRequest request) {
        return planner.setUp(request);
    }

    /** Posts from today on that aren't posted yet, and missed ones from the past week. */
    @GetMapping("/upcoming")
    public List<PostResponse> upcoming() {
        return marketing.upcoming();
    }

    /** The team's style and posting days, and the focus of {@code month} (YYYY-MM, this month if left out). */
    @GetMapping("/plan")
    public PlanResponse plan(@RequestParam(required = false) String month) {
        YearMonth chosen;
        try {
            chosen = month == null || month.isBlank() ? null : YearMonth.parse(month);
        } catch (DateTimeParseException unreadable) {
            throw new BadRequestException("Pick a month");
        }
        return marketing.plan(chosen);
    }

    @PutMapping("/plan")
    public PlanResponse savePlan(@Valid @RequestBody PlanRequest request) {
        return marketing.savePlan(request);
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

    /** Adds or replaces the post's picture: the file itself as the body. */
    @PostMapping("/posts/{id}/image")
    public PostResponse uploadPicture(@PathVariable Long id, HttpServletRequest request) throws IOException {
        return pictures.upload(id, request.getInputStream());
    }

    /** Has AI make the post's picture, replacing any it had. */
    @PostMapping("/posts/{id}/image/generate")
    public PictureResponse generatePicture(@PathVariable Long id,
                                           @Valid @RequestBody(required = false) PictureRequest request) {
        return pictures.generate(id, request);
    }

    /** The picture. Its address changes with the picture, so browsers can keep it. */
    @GetMapping("/posts/{id}/image")
    public ResponseEntity<byte[]> picture(@PathVariable Long id) {
        PostPictures.Picture picture = pictures.read(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .contentType(MediaType.parseMediaType(picture.contentType()))
                .contentLength(picture.content().length)
                .body(picture.content());
    }

    @DeleteMapping("/posts/{id}/image")
    public PostResponse removePicture(@PathVariable Long id) {
        return pictures.remove(id);
    }

    /** Posts it to its owner's LinkedIn profile, with its picture, and marks it posted. */
    @PostMapping("/posts/{id}/publish")
    public PostResponse publish(@PathVariable Long id) {
        return publisher.publish(id);
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

    /** The team's post templates, by name. */
    @GetMapping("/templates")
    public List<PostTemplateResponse> templates() {
        return templates.list();
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public PostTemplateResponse createTemplate(@Valid @RequestBody PostTemplateRequest request) {
        return templates.create(request);
    }

    @PutMapping("/templates/{id}")
    public PostTemplateResponse updateTemplate(@PathVariable Long id, @Valid @RequestBody PostTemplateRequest request) {
        return templates.update(id, request);
    }

    /** Deletes the template. Posts made from it keep their text. */
    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable Long id) {
        templates.delete(id);
    }
}
