package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostTheme;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostedRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.ResultsRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.WeekResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * The content calendar. Anyone on the team can add, plan and change posts; each has an owner who
 * writes and posts it. Posting itself happens on LinkedIn, by hand.
 */
@Service
@Transactional(readOnly = true)
public class MarketingService {

    private final ContentPostRepository repository;
    private final CurrentUserService currentUser;
    private final Clock clock;

    public MarketingService(ContentPostRepository repository, CurrentUserService currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** The week holding {@code day} (today if null), Monday to Sunday. */
    public WeekResponse week(LocalDate day) {
        LocalDate start = (day == null ? LocalDate.now(clock) : day)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(6);
        List<PostResponse> planned = repository.findPlanned(start, end).stream().map(PostResponse::from).toList();
        List<PostResponse> ideas = repository.findUnplanned(PostStatus.POSTED).stream().map(PostResponse::from).toList();
        int posted = (int) planned.stream().filter(post -> post.status() == PostStatus.POSTED).count();
        int ready = (int) planned.stream().filter(post -> post.status() == PostStatus.READY).count();
        return new WeekResponse(start, end, planned, ideas, posted, ready);
    }

    /** Every post, newest first. */
    public List<PostResponse> list() {
        return repository.findAllWithOwner().stream().map(PostResponse::from).toList();
    }

    public PostResponse get(Long id) {
        return PostResponse.from(find(id));
    }

    @Transactional
    public PostResponse create(PostRequest request) {
        ContentPost post = new ContentPost();
        apply(request, post);
        if (post.getOwner() == null) {
            post.setOwner(currentUser.get());
        }
        return PostResponse.from(repository.save(post));
    }

    @Transactional
    public PostResponse update(Long id, PostRequest request) {
        ContentPost post = find(id);
        apply(request, post);
        return PostResponse.from(post);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(find(id));
    }

    /** Records that the post went up on LinkedIn, on the day it did. */
    @Transactional
    public PostResponse markPosted(Long id, PostedRequest request) {
        ContentPost post = find(id);
        LocalDate day = request.postedOn() == null ? LocalDate.now(clock) : request.postedOn();
        post.setStatus(PostStatus.POSTED);
        post.setPostedOn(day);
        if (post.getPlannedFor() == null) {
            post.setPlannedFor(day);
        }
        String url = Text.clean(request.postUrl());
        if (url != null) {
            post.setPostUrl(url);
        }
        return PostResponse.from(post);
    }

    @Transactional
    public PostResponse saveResults(Long id, ResultsRequest request) {
        ContentPost post = find(id);
        post.setImpressions(request.impressions());
        post.setReactions(request.reactions());
        post.setComments(request.comments());
        post.setConversations(request.conversations());
        return PostResponse.from(post);
    }

    ContentPost find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Post not found"));
    }

    private void apply(PostRequest request, ContentPost post) {
        post.setTitle(request.title().trim());
        post.setBody(request.body() == null || request.body().isBlank() ? null : request.body().strip());
        post.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().strip());
        post.setTheme(request.theme() == null ? PostTheme.OTHER : request.theme());
        post.setAccount(request.account() == null ? PostAccount.PERSONAL : request.account());
        post.setStatus(status(request, post));
        post.setPlannedFor(request.plannedFor());
        if (post.getStatus() == PostStatus.POSTED && post.getPostedOn() == null) {
            post.setPostedOn(request.plannedFor() == null ? LocalDate.now(clock) : request.plannedFor());
        }
        if (post.getStatus() != PostStatus.POSTED) {
            post.setPostedOn(null);
        }
        // Left out, an edit keeps the owner, and a new post gets the person saving it (see create).
        if (request.ownerId() != null) {
            post.setOwner(currentUser.findOwner(request.ownerId()));
        }
    }

    /** As asked; otherwise an idea with words in it is a draft, and a bare idea stays an idea. */
    private static PostStatus status(PostRequest request, ContentPost post) {
        if (request.status() != null) {
            return request.status();
        }
        if (post.getStatus() != null && post.getStatus() != PostStatus.IDEA) {
            return post.getStatus();
        }
        return request.body() == null || request.body().isBlank() ? PostStatus.IDEA : PostStatus.DRAFT;
    }
}
