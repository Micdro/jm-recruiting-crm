package com.janemichael.jmrecruitingcrm.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.janemichael.jmrecruitingcrm.ai.AiRequest;
import com.janemichael.jmrecruitingcrm.ai.AiRequest.Field;
import com.janemichael.jmrecruitingcrm.ai.AiResult;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SetupRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SetupResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Sets up a week: one AI call writes a draft for every posting day in the plan that's still open
 * that week, in the team's style, and saves them as drafts on the calendar for the team to review.
 * Days already past, and days that already have that person's post, are left alone.
 */
@Service
public class WeekPlanner {

    static final String POSTS = "posts";
    static final int TOKENS_PER_POST = 1_200;
    static final int MAX_TOKENS = 12_000;
    /** Posts other than these the AI is told about, so the week doesn't repeat them. */
    static final int RECENT_SHOWN = 20;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);

    /** An open posting day: when, and the plan's slot for it. */
    record Opening(int number, LocalDate date, ContentPlan.Slot slot, String author) {
    }

    private final ContentPlan plan;
    private final ContentPostRepository posts;
    private final MarketingService marketing;
    private final AppUserRepository users;
    private final CurrentUserService currentUser;
    private final AiService ai;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public WeekPlanner(ContentPlan plan, ContentPostRepository posts, MarketingService marketing, AppUserRepository users,
                       CurrentUserService currentUser, AiService ai, PlatformTransactionManager transactionManager,
                       Clock clock) {
        this.plan = plan;
        this.posts = posts;
        this.marketing = marketing;
        this.users = users;
        this.currentUser = currentUser;
        this.ai = ai;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public SetupResponse setUp(SetupRequest request) {
        LocalDate today = LocalDate.now(clock);
        LocalDate monday = (request == null || request.date() == null ? today : request.date())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ContentPlan.Values rules = plan.values(YearMonth.from(monday.plusDays(3)));
        if (rules.rhythm().isEmpty()) {
            throw new BadRequestException("Pick your posting days under Plan first. The week is set up from them.");
        }

        record Week(List<Opening> open, int skipped, List<String> recent) {
        }
        Week week = transaction.execute(status -> {
            List<ContentPost> planned = posts.findPlanned(monday, monday.plusDays(6));
            Map<Long, String> names = new HashMap<>();
            users.findAllById(rules.rhythm().stream().map(ContentPlan.Slot::ownerId).filter(Objects::nonNull).toList())
                    .forEach(user -> names.put(user.getId(), PostDrafter.firstName(user.getDisplayName())));
            List<Opening> open = new ArrayList<>();
            int skipped = 0;
            for (ContentPlan.Slot slot : rules.rhythm()) {
                LocalDate date = monday.plusDays(slot.day().getValue() - 1L);
                if (date.isBefore(today) || taken(planned, date, slot)) {
                    skipped++;
                } else {
                    open.add(new Opening(open.size() + 1, date, slot, names.get(slot.ownerId())));
                }
            }
            List<String> recent = posts.findAllWithOwner().stream().limit(RECENT_SHOWN).map(ContentPost::getTitle).toList();
            return new Week(open, skipped, recent);
        });
        if (week.open().isEmpty()) {
            throw new BadRequestException(week.skipped() > 0
                    ? "Every posting day left in this week already has a post."
                    : "There are no posting days left in this week.");
        }

        String focus = request == null ? null : Text.clean(request.focus());
        AiResult result = ai.write("Setting up the week", new AiRequest(
                PostDrafter.systemPrompt(rules.style()),
                prompt(monday, week.open(), rules.focus(), focus, week.recent()),
                List.of(Field.objects(POSTS, "One post for each numbered slot, in the same order.", List.of(
                        Field.text("slot", "The slot's number, like 1."),
                        Field.text("title", "A short title for the team's calendar, under 12 words."),
                        Field.text("body", "The post, ready to paste into LinkedIn.")))),
                Math.min(MAX_TOKENS, TOKENS_PER_POST * week.open().size() + 500)));

        Map<Integer, JsonNode> written = new HashMap<>();
        List<JsonNode> answers = result.objects(POSTS);
        for (int index = 0; index < answers.size(); index++) {
            JsonNode answer = answers.get(index);
            Integer number = number(answer.path("slot").asText(""));
            written.putIfAbsent(number == null ? index + 1 : number, answer);
        }

        AppUser me = currentUser.get();
        List<PostResponse> created = transaction.execute(status -> {
            List<ContentPost> saved = new ArrayList<>();
            for (Opening opening : week.open()) {
                JsonNode answer = written.get(opening.number());
                String body = answer == null ? "" : fit(PostDrafter.clean(answer.path("body").asText("")));
                if (body.isBlank()) {
                    continue;
                }
                String title = PostDrafter.clean(answer.path("title").asText(""));
                ContentPost post = new ContentPost();
                post.setTitle(title.isBlank() ? "Post for " + opening.date().getDayOfWeek()
                        .getDisplayName(TextStyle.FULL, Locale.US) : title.substring(0, Math.min(200, title.length())));
                post.setBody(body);
                post.setTheme(opening.slot().theme());
                post.setAccount(opening.slot().account());
                post.setStatus(PostStatus.DRAFT);
                post.setPlannedFor(opening.date());
                post.setOwner(opening.slot().ownerId() == null ? me
                        : users.findById(opening.slot().ownerId()).orElse(me));
                saved.add(posts.save(post));
            }
            return marketing.responses(saved);
        });
        if (created.isEmpty()) {
            throw new BadRequestException("The AI didn't write the week's posts. Try again.");
        }
        return new SetupResponse(created, week.skipped() + week.open().size() - created.size(),
                result.provider().label(), result.costCents());
    }

    /** Whether that person (or the company page) already has a post that day. */
    static boolean taken(List<ContentPost> planned, LocalDate date, ContentPlan.Slot slot) {
        return planned.stream().anyMatch(post -> date.equals(post.getPlannedFor())
                && post.getAccount() == slot.account()
                && (slot.account() == PostAccount.COMPANY_PAGE
                    || Objects.equals(post.getOwner() == null ? null : post.getOwner().getId(), slot.ownerId())));
    }

    static String prompt(LocalDate monday, List<Opening> open, String monthFocus, String weekFocus, List<String> recent) {
        StringBuilder text = new StringBuilder();
        text.append("Write ").append(open.size()).append(open.size() == 1 ? " LinkedIn post" : " LinkedIn posts")
                .append(" for the week of ").append(monday.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)))
                .append(", one for each numbered slot below. Make each a different idea, open each a different way, ")
                .append("and keep each to 120 to 220 words.\n\n");
        if (monthFocus != null && !monthFocus.isBlank()) {
            text.append("The team's focus this month: ").append(monthFocus.trim()).append('\n');
        }
        if (weekFocus != null && !weekFocus.isBlank()) {
            text.append("Focus this week: ").append(weekFocus.trim()).append('\n');
        }
        text.append("\nSlots:\n");
        for (Opening opening : open) {
            text.append(opening.number()).append(". ").append(opening.date().format(DAY)).append(". ");
            String theme = PostDrafter.theme(opening.slot().theme());
            text.append(theme == null ? "Any topic that fits the firm." : "About " + theme + ".");
            if (opening.slot().account() == PostAccount.COMPANY_PAGE) {
                text.append(" For the firm's company page.");
            } else {
                text.append(" For ").append(opening.author() == null ? "the author" : opening.author())
                        .append("'s own profile.");
            }
            text.append('\n');
        }
        if (!recent.isEmpty()) {
            text.append("\nRecent and planned posts. Don't repeat these:\n");
            recent.forEach(title -> text.append("- ").append(title).append('\n'));
        }
        return text.toString();
    }

    /** A post longer than LinkedIn allows is cut at its last full paragraph that fits. */
    static String fit(String body) {
        if (body.length() <= MarketingDtos.POST_LIMIT) {
            return body;
        }
        String cut = body.substring(0, MarketingDtos.POST_LIMIT);
        int paragraph = cut.lastIndexOf("\n\n");
        return (paragraph > MarketingDtos.POST_LIMIT / 2 ? cut.substring(0, paragraph) : cut).trim();
    }

    private static Integer number(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty() || digits.length() > 3) {
            return null;
        }
        return Integer.parseInt(digits);
    }
}
