package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.common.Auditable;
import com.janemichael.jmrecruitingcrm.common.EncryptedString;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
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

/**
 * A saved starting point for a post: what fills in when someone picks it while adding or writing
 * a post. Any of the title, text and notes can be left empty; what's empty leaves the post as it is.
 */
@Entity
@Table(name = "post_template")
public class PostTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the team calls it in the list, like "Weekly hiring market note". */
    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String name;

    @Convert(converter = EncryptedString.class)
    private String title;

    @Convert(converter = EncryptedString.class)
    private String body;

    @Convert(converter = EncryptedString.class)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostTheme theme = PostTheme.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostAccount account = PostAccount.PERSONAL;

    /** Who made it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }
}
