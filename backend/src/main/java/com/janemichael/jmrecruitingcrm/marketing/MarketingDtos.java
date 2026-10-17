package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostTheme;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
            Instant updatedAt
    ) {

        public static PostResponse from(ContentPost post) {
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
                    post.getUpdatedAt());
        }
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
