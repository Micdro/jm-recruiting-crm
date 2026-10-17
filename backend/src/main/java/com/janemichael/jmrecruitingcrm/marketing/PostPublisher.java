package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.linkedin.LinkedInService;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posts a post straight to its owner's LinkedIn profile, with its picture, and marks it posted.
 * Only the owner can, from a computer where they've connected their LinkedIn. Company page posts
 * stay copy-and-paste until LinkedIn approves the firm's app for page posting.
 */
@Service
public class PostPublisher {

    private final MarketingService marketing;
    private final PostPictures pictures;
    private final LinkedInService linkedIn;
    private final CurrentUserService currentUser;
    private final TransactionTemplate transaction;
    private final Clock clock;
    /** Posts being sent right now, so a second click can't post one twice. */
    private final Set<Long> sending = ConcurrentHashMap.newKeySet();

    public PostPublisher(MarketingService marketing, PostPictures pictures, LinkedInService linkedIn,
                         CurrentUserService currentUser, PlatformTransactionManager transactionManager, Clock clock) {
        this.marketing = marketing;
        this.pictures = pictures;
        this.linkedIn = linkedIn;
        this.currentUser = currentUser;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public PostResponse publish(Long postId) {
        AppUser me = currentUser.get();
        if (!sending.add(postId)) {
            throw new ConflictException("This post is already being sent to LinkedIn.");
        }
        try {
            record Ready(String text, boolean hasPicture) {
            }
            Ready ready = transaction.execute(status -> {
                ContentPost post = marketing.find(postId);
                if (post.getStatus() == PostStatus.POSTED) {
                    throw new ConflictException("This post is already on LinkedIn.");
                }
                if (post.getBody() == null || post.getBody().isBlank()) {
                    throw new BadRequestException("Write the post before posting it.");
                }
                if (post.getAccount() == PostAccount.COMPANY_PAGE) {
                    throw new BadRequestException("Company page posts can't go straight to LinkedIn until LinkedIn "
                            + "approves the firm's app for page posting. Copy the post and post it on the page.");
                }
                AppUser owner = post.getOwner();
                if (owner != null && !owner.getId().equals(me.getId())) {
                    throw new BadRequestException("This post goes on " + owner.getDisplayName() + "'s profile, so "
                            + PostDrafter.firstName(owner.getDisplayName()) + " posts it from their CRM.");
                }
                return new Ready(post.getBody(), post.getImageKey() != null);
            });
            byte[] picture = ready.hasPicture() ? pictures.read(postId).content() : null;

            String url = linkedIn.publish(me, new LinkedInService.Post(ready.text(), picture));

            return transaction.execute(status -> {
                ContentPost post = marketing.find(postId);
                post.setStatus(PostStatus.POSTED);
                post.setPostedOn(LocalDate.now(clock));
                if (post.getPlannedFor() == null) {
                    post.setPlannedFor(post.getPostedOn());
                }
                post.setPostUrl(url);
                if (post.getOwner() == null) {
                    post.setOwner(me);
                }
                return marketing.response(post);
            });
        } finally {
            sending.remove(postId);
        }
    }
}
