package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostTheme;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Request and response shapes for the Marketing page. */
public final class MarketingDtos {

    /** LinkedIn's limit for a post. */
    public static final int POST_LIMIT = 3_000;

    private MarketingDtos() {
    }

    /**
     * A new or changed post. An idea needs only a title.
     *
     * @param ownerId who writes and posts it; the person saving it if left out
     */
    public record PostRequest(
            @NotBlank(message = "Give the post a title")
            @Size(max = 200, message = "The title must be 200 characters or fewer")
            String title,

            @Size(max = POST_LIMIT, message = "LinkedIn posts can be up to 3,000 characters")
            String body,

            @Size(max = 2_000, message = "Notes must be 2,000 characters or fewer")
            String notes,

            PostTheme theme,
            PostAccount account,
            PostStatus status,
            LocalDate plannedFor,
            Long ownerId
    ) {
    }

    /** Marks a post as posted. The day defaults to today. */
    public record PostedRequest(
            LocalDate postedOn,

            @Size(max = 500, message = "The link must be 500 characters or fewer")
            String postUrl
    ) {
    }

    /** LinkedIn's numbers for a post, entered by hand. Any can be left blank. */
    public record ResultsRequest(
            @Min(value = 0, message = "Impressions can't be negative") Integer impressions,
            @Min(value = 0, message = "Reactions can't be negative") Integer reactions,
            @Min(value = 0, message = "Comments can't be negative") Integer comments,
            @Min(value = 0, message = "Conversations can't be negative") Integer conversations
    ) {
    }

    public record PostResponse(
            Long id,
            String title,
            String body,
            String notes,
            PostTheme theme,
            PostAccount account,
            PostStatus status,
            LocalDate plannedFor,
            LocalDate postedOn,
            String postUrl,
            Integer impressions,
            Integer reactions,
            Integer comments,
            Integer conversations,
            Long ownerId,
            String ownerName,
            Instant updatedAt,
            Picture picture
    ) {

        /** A post without its picture's state; see {@link #from(ContentPost, boolean)}. */
        public static PostResponse from(ContentPost post) {
            return from(post, true);
        }

        /**
         * @param pictureHere whether the picture's file is on this computer yet (one added on another
         *                    computer arrives through the sync folder a little after the post)
         */
        public static PostResponse from(ContentPost post, boolean pictureHere) {
            return new PostResponse(
                    post.getId(),
                    post.getTitle(),
                    post.getBody(),
                    post.getNotes(),
                    post.getTheme(),
                    post.getAccount(),
                    post.getStatus(),
                    post.getPlannedFor(),
                    post.getPostedOn(),
                    post.getPostUrl(),
                    post.getImpressions(),
                    post.getReactions(),
                    post.getComments(),
                    post.getConversations(),
                    post.getOwner() == null ? null : post.getOwner().getId(),
                    post.getOwner() == null ? null : post.getOwner().getDisplayName(),
                    post.getUpdatedAt(),
                    post.getImageKey() == null ? null : new Picture(
                            "/api/marketing/posts/" + post.getId() + "/image?v=" + post.getImageKey().substring(0, 8),
                            post.getImageSource(), pictureHere));
        }
    }

    /**
     * A post's picture.
     *
     * @param url    changes whenever the picture does, so browsers never show an old one
     * @param source UPLOAD or AI
     * @param ready  whether its file is on this computer yet
     */
    public record Picture(String url, String source, boolean ready) {
    }

    /** Asks AI for a picture for the post. The picture replaces any it had. */
    public record PictureRequest(
            @Size(max = 500, message = "Keep the direction to 500 characters or fewer")
            String direction
    ) {
    }

    public record PictureResponse(PostResponse post, int costCents) {
    }

    /** One posting day in the plan. A day can have several, for different people or the company page. */
    public record SlotRequest(
            @NotNull(message = "Pick a day") DayOfWeek day,
            PostTheme theme,
            PostAccount account,
            Long ownerId
    ) {
    }

    /**
     * The team's style and plan, and one month's focus.
     *
     * @param month YYYY-MM
     */
    public record PlanRequest(
            @Size(max = 4_000, message = "Keep the style to 4,000 characters or fewer")
            String style,

            @Size(max = 1_000, message = "Keep the picture style to 1,000 characters or fewer")
            String pictureStyle,

            @Valid
            @Size(max = 21, message = "Plan up to 21 posts a week")
            List<SlotRequest> rhythm,

            @NotNull(message = "Pick a month")
            @Pattern(regexp = "\\d{4}-\\d{2}", message = "Pick a month")
            String month,

            @Size(max = 2_000, message = "Keep the month's focus to 2,000 characters or fewer")
            String focus
    ) {
    }

    public record SlotResponse(DayOfWeek day, PostTheme theme, PostAccount account, Long ownerId, String ownerName) {
    }

    public record PlanResponse(String style, String pictureStyle, List<SlotResponse> rhythm, String month, String focus) {
    }

    /**
     * Fills a week's open posting days with drafts.
     *
     * @param date  any day of the week (this week if left out)
     * @param focus anything this week should be about, on top of the month's focus
     */
    public record SetupRequest(
            LocalDate date,

            @Size(max = 1_000, message = "Keep the week's focus to 1,000 characters or fewer")
            String focus
    ) {
    }

    /** @param skipped posting days left alone: already planned, or already past */
    public record SetupResponse(List<PostResponse> created, int skipped, String provider, int costCents) {
    }

    /**
     * One week of the content calendar, Monday to Sunday, and the ideas waiting for a date.
     *
     * @param posted posts this week already on LinkedIn
     * @param ready  posts written and approved, waiting for their day
     */
    public record WeekResponse(LocalDate start, LocalDate end, List<PostResponse> planned, List<PostResponse> ideas,
                               int posted, int ready) {
    }

    /** @param direction anything extra for this draft, like "shorter" or "open with the Series B news" */
    public record DraftRequest(
            @Size(max = 1_000, message = "Keep the direction to 1,000 characters or fewer")
            String direction
    ) {
    }

    /** A draft for the person to read and edit. It isn't saved until they save the post. */
    public record DraftResponse(String body, String provider, int costCents) {
    }

    public record SuggestRequest(
            @NotNull(message = "Choose how many ideas")
            @Min(value = 1, message = "Ask for at least one idea")
            @Max(value = 10, message = "Ask for up to 10 ideas at a time")
            Integer count,

            @Size(max = 500, message = "Keep the focus to 500 characters or fewer")
            String focus
    ) {
    }

    /** Suggested titles, not saved. The person adds the ones they like as ideas. */
    public record SuggestResponse(List<String> ideas, String provider, int costCents) {
    }
}
