package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.common.Auditable;
import com.janemichael.jmrecruitingcrm.common.EncryptedString;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostTheme;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** A LinkedIn post, from idea to posted, with what it led to. */
@Entity
@Table(name = "content_post")
public class ContentPost extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** A one-line idea, like "Why banks hire FDEs before platform engineers". */
    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String title;

    /** The post as it will go on LinkedIn. */
    @Convert(converter = EncryptedString.class)
    private String body;

    /** Facts and points to make. AI drafts use only what's here and in the title. */
    @Convert(converter = EncryptedString.class)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostTheme theme = PostTheme.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostAccount account = PostAccount.PERSONAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostStatus status = PostStatus.IDEA;

    @Column(name = "planned_for")
    private LocalDate plannedFor;

    @Column(name = "posted_on")
    private LocalDate postedOn;

    @Column(name = "post_url")
    @Convert(converter = EncryptedString.class)
    private String postUrl;

    // Results, entered by hand from LinkedIn's analytics.
    private Integer impressions;
    private Integer reactions;
    private Integer comments;
    /** DMs or calls the post led to: the number that matters. */
    private Integer conversations;

    /** Who writes and posts it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    /** The picture's file in file_blob, encrypted like candidates' files; null when there's none. */
    @Column(name = "image_key")
    private String imageKey;

    /** image/jpeg, image/png, image/gif or image/webp. */
    @Column(name = "image_type")
    private String imageType;

    /** UPLOAD or AI. */
    @Column(name = "image_source")
    private String imageSource;

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public PostTheme getTheme() {
        return theme;
    }

    public void setTheme(PostTheme theme) {
        this.theme = theme;
    }

    public PostAccount getAccount() {
        return account;
    }

    public void setAccount(PostAccount account) {
        this.account = account;
    }

    public PostStatus getStatus() {
        return status;
    }

    public void setStatus(PostStatus status) {
        this.status = status;
    }

    public LocalDate getPlannedFor() {
        return plannedFor;
    }

    public void setPlannedFor(LocalDate plannedFor) {
        this.plannedFor = plannedFor;
    }

    public LocalDate getPostedOn() {
        return postedOn;
    }

    public void setPostedOn(LocalDate postedOn) {
        this.postedOn = postedOn;
    }

    public String getPostUrl() {
        return postUrl;
    }

    public void setPostUrl(String postUrl) {
        this.postUrl = postUrl;
    }

    public Integer getImpressions() {
        return impressions;
    }

    public void setImpressions(Integer impressions) {
        this.impressions = impressions;
    }

    public Integer getReactions() {
        return reactions;
    }

    public void setReactions(Integer reactions) {
        this.reactions = reactions;
    }

    public Integer getComments() {
        return comments;
    }

    public void setComments(Integer comments) {
        this.comments = comments;
    }

    public Integer getConversations() {
        return conversations;
    }

    public void setConversations(Integer conversations) {
        this.conversations = conversations;
    }

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }

    public String getImageKey() {
        return imageKey;
    }

    public String getImageType() {
        return imageType;
    }

    public String getImageSource() {
        return imageSource;
    }

    /** Sets or, with nulls, removes the picture. */
    public void setImage(String key, String type, String source) {
        this.imageKey = key;
        this.imageType = type;
        this.imageSource = source;
    }
}
