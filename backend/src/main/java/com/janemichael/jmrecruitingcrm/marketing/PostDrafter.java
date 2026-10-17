package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.ai.AiRequest;
import com.janemichael.jmrecruitingcrm.ai.AiRequest.Field;
import com.janemichael.jmrecruitingcrm.ai.AiResult;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostTheme;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.DraftRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.DraftResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SuggestRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.SuggestResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Drafts posts and suggests ideas with whichever AI the account owner chose. Drafts come back for
 * the person to read and edit; nothing is saved or posted until they do it themselves. The AI call
 * runs outside any database transaction, since it can take a minute.
 */
@Service
public class PostDrafter {

    static final String BODY = "body";
    static final String IDEAS = "ideas";
    static final int DRAFT_MAX_TOKENS = 1_500;
    static final int IDEAS_MAX_TOKENS = 1_000;
    /** Recent titles shown to the AI so it doesn't suggest the same thing again. */
    static final int RECENT_SHOWN = 20;

    /** What a draft is built from, read before calling the AI. */
    record Facts(String title, String notes, PostTheme theme, PostAccount account, String author, String current,
                 String direction, String focus, String style) {
    }

    private final MarketingService marketing;
    private final ContentPostRepository posts;
    private final ContentPlan plan;
    private final AiService ai;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PostDrafter(MarketingService marketing, ContentPostRepository posts, ContentPlan plan, AiService ai,
                       PlatformTransactionManager transactionManager, Clock clock) {
        this.marketing = marketing;
        this.posts = posts;
        this.plan = plan;
        this.ai = ai;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setReadOnly(true);
        this.clock = clock;
    }

    public DraftResponse draft(Long postId, DraftRequest request) {
        Facts facts = transaction.execute(status -> {
            ContentPost post = marketing.find(postId);
            // The plan of the month the post goes up in.
            ContentPlan.Values rules = plan.values(
                    YearMonth.from(post.getPlannedFor() == null ? LocalDate.now(clock) : post.getPlannedFor()));
            return new Facts(post.getTitle(), post.getNotes(), post.getTheme(), post.getAccount(),
                    post.getOwner() == null ? null : firstName(post.getOwner().getDisplayName()),
                    post.getBody(), request == null ? null : Text.clean(request.direction()), rules.focus(),
                    rules.style());
        });
        AiResult result = ai.write("Drafting", new AiRequest(systemPrompt(facts.style()), draftMessage(facts),
                List.of(Field.text(BODY, "The post, ready to paste into LinkedIn.")), DRAFT_MAX_TOKENS));
        String body = clean(result.text(BODY));
        if (body.isBlank()) {
            throw new BadRequestException("The AI didn't write a post. Try again.");
        }
        return new DraftResponse(body, result.provider().label(), result.costCents());
    }

    public SuggestResponse suggest(SuggestRequest request) {
        List<String> recent = transaction.execute(status -> posts.findAllWithOwner().stream()
                .limit(RECENT_SHOWN)
                .map(ContentPost::getTitle)
                .toList());
        ContentPlan.Values rules = plan.values(YearMonth.now(clock));
        AiResult result = ai.write("Suggesting ideas", new AiRequest(systemPrompt(rules.style()),
                ideasMessage(request.count(), Text.clean(request.focus()), rules.focus(), recent),
                List.of(Field.list(IDEAS, "One-line post ideas, each specific enough to write a post from.")),
                IDEAS_MAX_TOKENS));
        List<String> ideas = result.list(IDEAS).stream()
                .map(PostDrafter::clean)
                .filter(idea -> !idea.isBlank())
                .limit(request.count())
                .toList();
        if (ideas.isEmpty()) {
            throw new BadRequestException("The AI didn't suggest anything. Try again.");
        }
        return new SuggestResponse(ideas, result.provider().label(), result.costCents());
    }

    // What the AI is told -------------------------------------------------------------------

    /** The firm's rules, then the team's own style guide when there is one. */
    static String systemPrompt(String style) {
        if (style == null || style.isBlank()) {
            return systemPrompt();
        }
        return systemPrompt() + """

                The team's style guide. Follow it. Where it differs from the rules above, the style guide wins, \
                except the rules about using only the facts given and never naming a client or a candidate.
                """ + style.trim() + "\n";
    }

    static String systemPrompt() {
        return """
                You write LinkedIn content for Jane Michael AI Talent Search, a recruiting firm that places \
                Forward Deployed Engineers and AI Platform Engineers (MLOps/LLMOps) with AI and cybersecurity \
                vendors that sell to banks, insurers and other financial services firms. Readers are engineering \
                and hiring leaders at those vendors, and engineers who might want those jobs.

                How to write a post:
                - One idea per post. Make the first line a specific, concrete observation, since LinkedIn shows \
                only the first two lines before "see more".
                - Short paragraphs of one to three sentences, 120 to 220 words in all.
                - End with one clear call to action: a question for the comments, or an invitation to message \
                the author.
                - Plain, direct sentences. Sound like a founder who works in this market every day, not marketing.
                - No em dashes or en dashes. No buzzwords such as "leverage", "synergy", "game-changer", \
                "cutting-edge" or "in today's fast-paced world". No emojis. At most three hashtags, on the last line.
                - Use only the facts given. Never invent numbers, statistics, names, clients, candidates or \
                placements. Never name a client or a candidate.
                - For the company page, write as the firm ("we"). For a personal post, write as the author, in \
                the first person.
                """;
    }

    static String draftMessage(Facts facts) {
        StringBuilder text = new StringBuilder();
        text.append("Write a LinkedIn post for ")
                .append(facts.account() == PostAccount.COMPANY_PAGE ? "the firm's company page" : "the author's own profile")
                .append(".\n\n");
        line(text, "Author", facts.author());
        line(text, "Topic", facts.title());
        line(text, "Theme", theme(facts.theme()));
        line(text, "Notes and facts to use", facts.notes());
        line(text, "The team's focus this month", facts.focus());
        line(text, "Direction for this draft", facts.direction());
        if (facts.current() != null && !facts.current().isBlank()) {
            text.append("\nThe current draft. Improve it rather than starting over, and keep what works:\n")
                    .append(facts.current().trim()).append('\n');
        }
        return text.toString();
    }

    static String ideasMessage(int count, String focus, String monthFocus, List<String> recent) {
        StringBuilder text = new StringBuilder();
        text.append("Suggest ").append(count).append(count == 1 ? " idea" : " ideas")
                .append(" for this week's LinkedIn posts. Mix the topics: hiring forward deployed engineers, ")
                .append("hiring AI platform engineers, and AI and security at banks and insurers.\n");
        line(text, "The team's focus this month", monthFocus);
        line(text, "Focus this week", focus);
        if (!recent.isEmpty()) {
            text.append("\nRecent and planned posts. Don't repeat these:\n");
            recent.forEach(title -> text.append("- ").append(title).append('\n'));
        }
        return text.toString();
    }

    static String theme(PostTheme theme) {
        if (theme == null) {
            return null;
        }
        return switch (theme) {
            case FDE_HIRING -> "hiring forward deployed engineers";
            case AI_PLATFORM_HIRING -> "hiring AI platform engineers (MLOps/LLMOps)";
            case BFSI_AI_SECURITY -> "AI and security at banks, insurers and financial services firms";
            case FIRM_NEWS -> "news from the firm";
            case OTHER -> null;
        };
    }

    // Small helpers -------------------------------------------------------------------------

    /** Dashes become commas, and stray whitespace goes. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s*[\\u2014\\u2013]\\s*", ", ")
                .replaceAll(",\\s*,", ",")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim().split("\\s+")[0];
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(value.trim()).append('\n');
        }
    }
}
