package com.janemichael.jmrecruitingcrm.template;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.Auditable;
import com.janemichael.jmrecruitingcrm.common.EncryptedString;
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

/** A saved LinkedIn or email message for a touch in the outreach sequence. */
@Entity
@Table(name = "message_template")
public class MessageTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String name;

    /** EMAIL or LINKEDIN. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionType channel;

    /** Which touch of the sequence it's for (1 to 4), or null for any. */
    private Integer touch;

    @Convert(converter = EncryptedString.class)
    private String subject;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String body;

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

    public ActionType getChannel() {
        return channel;
    }

    public void setChannel(ActionType channel) {
        this.channel = channel;
    }

    public Integer getTouch() {
        return touch;
    }

    public void setTouch(Integer touch) {
        this.touch = touch;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }
}
