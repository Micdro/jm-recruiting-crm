package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostTheme;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PlanRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PlanResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostedRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.ResultsRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SlotResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.WeekResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The content calendar. Anyone on the team can add, plan and change posts; each has an owner who
 * writes and posts it. Posting itself happens on LinkedIn, by hand.
 */
@Service
@Transactional(readOnly = true)
public class MarketingService {

    /** How far back Upcoming still shows posts that weren't posted on their day. */
    static final int OVERDUE_DAYS = 7;

    private final ContentPostRepository repository;
    private final CurrentUserService currentUser;
    private final AppUserRepository users;
    private final ContentPlan plan;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MarketingService(ContentPostRepository repository, CurrentUserService currentUser, AppUserRepository users,
                            ContentPlan plan, JdbcTemplate jdbc, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.users = users;
        this.plan = plan;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The week holding {@code day} (today if null), Monday to Sunday. */
    public WeekResponse week(LocalDate day) {
        LocalDate start = (day == null ? LocalDate.now(clock) : day)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(6);
        List<PostResponse> planned = responses(repository.findPlanned(start, end));
        List<PostResponse> ideas = responses(repository.findUnplanned(PostStatus.POSTED));
        int posted = (int) planned.stream().filter(post -> post.status() == PostStatus.POSTED).count();
        int ready = (int) planned.stream().filter(post -> post.status() == PostStatus.READY).count();
        return new WeekResponse(start, end, planned, ideas, posted, ready);
    }

    /** Every post, newest first. */
    public List<PostResponse> list() {
        return responses(repository.findAllWithOwner());
    }

    /** Posts from today on that aren't posted yet, by day, and ones from the past week that were missed. */
    public List<PostResponse> upcoming() {
        return responses(repository.findUpcoming(LocalDate.now(clock).minusDays(OVERDUE_DAYS), PostStatus.POSTED));
    }

    public PostResponse get(Long id) {
        return response(find(id));
    }

    @Transactional
    public PostResponse create(PostRequest request) {
        ContentPost post = new ContentPost();
        apply(request, post);
        if (post.getOwner() == null) {
            post.setOwner(currentUser.get());
        }
        return response(repository.save(post));
    }

    @Transactional
    public PostResponse update(Long id, PostRequest request) {
        ContentPost post = find(id);
        apply(request, post);
        return response(post);
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
        return response(post);
    }

    @Transactional
    public PostResponse saveResults(Long id, ResultsRequest request) {
        ContentPost post = find(id);
        post.setImpressions(request.impressions());
        post.setReactions(request.reactions());
        post.setComments(request.comments());
        post.setConversations(request.conversations());
        return response(post);
    }

    // The plan ------------------------------------------------------------------------------

    /** The plan, with the focus of {@code month} (this month if null). */
    public PlanResponse plan(YearMonth month) {
        if (month == null) {
            month = YearMonth.now(clock);
        }
        ContentPlan.Values values = plan.values(month);
        Map<Long, String> names = new HashMap<>();
        users.findAllById(values.rhythm().stream().map(ContentPlan.Slot::ownerId).filter(Objects::nonNull).toList())
                .forEach(user -> names.put(user.getId(), user.getDisplayName()));
        return new PlanResponse(values.style(), values.pictureStyle(),
                values.rhythm().stream()
                        .map(slot -> new SlotResponse(slot.day(), slot.theme(), slot.account(), slot.ownerId(),
                                names.get(slot.ownerId())))
                        .toList(),
                month.toString(), values.focus());
    }

    @Transactional
    public PlanResponse savePlan(PlanRequest request) {
        YearMonth month = YearMonth.parse(request.month());
        List<ContentPlan.Slot> rhythm = (request.rhythm() == null ? List.<MarketingDtos.SlotRequest>of() : request.rhythm())
                .stream()
                .map(slot -> {
                    AppUser owner = currentUser.findOwner(slot.ownerId());
                    return new ContentPlan.Slot(slot.day(), slot.theme(), slot.account(), owner == null ? null : owner.getId());
                })
                .toList();
        plan.save(request.style(), request.pictureStyle(), rhythm, month, request.focus());
        return plan(month);
    }

    // Responses -----------------------------------------------------------------------------

    PostResponse response(ContentPost post) {
        return responses(List.of(post)).getFirst();
    }

    List<PostResponse> responses(List<ContentPost> posts) {
        Set<String> here = picturesHere(posts);
        return posts.stream()
                .map(post -> PostResponse.from(post, post.getImageKey() != null && here.contains(post.getImageKey())))
                .toList();
    }

    /** Which of these posts' pictures have reached this computer. */
    private Set<String> picturesHere(List<ContentPost> posts) {
        List<String> keys = posts.stream().map(ContentPost::getImageKey).filter(Objects::nonNull).distinct().toList();
        if (keys.isEmpty()) {
            return Set.of();
        }
        String marks = String.join(", ", Collections.nCopies(keys.size(), "?"));
        return new HashSet<>(jdbc.queryForList("SELECT blob_key FROM file_blob WHERE blob_key IN (" + marks + ")",
                String.class, keys.toArray()));
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
