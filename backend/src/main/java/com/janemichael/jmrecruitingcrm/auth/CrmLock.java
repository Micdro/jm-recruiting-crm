package com.janemichael.jmrecruitingcrm.auth;

import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.EncryptionBackfill;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.KeyWrap;
import com.janemichael.jmrecruitingcrm.common.KeyWrap.WrongPasswordException;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the desktop CRM locked unless someone is signed in.
 *
 * <p>The CRM key isn't stored on disk. Each account holds its own copy locked with that person's
 * password ({@link KeyWrap}). Signing in opens it, and the key stays in memory only while at least
 * one person is signed in on this computer. Signing out (or the session expiring) locks the CRM
 * again; restarting the app does too. Sync keeps running while locked, because it only copies
 * scrambled data.
 *
 * <p>A server is unlocked by its configured key and never locks. Accounts still get their locked
 * copy there, so a computer can later join the same CRM.
 */
@Component
public class CrmLock implements HttpSessionListener, HttpSessionIdListener {

    private static final Logger log = LoggerFactory.getLogger(CrmLock.class);

    /** Published once a signed-in account is known to have a copy of the key its password opens. */
    public record AccountCanUnlock(String email) {
    }

    private final FieldEncryptor encryptor;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EncryptionBackfill backfill;
    private final ApplicationEventPublisher events;
    private final boolean desktop;
    private final Set<String> unlockedSessions = ConcurrentHashMap.newKeySet();

    public CrmLock(
            FieldEncryptor encryptor,
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            EncryptionBackfill backfill,
            ApplicationEventPublisher events,
            @Value("${app.desktop.enabled:false}") boolean desktop
    ) {
        this.encryptor = encryptor;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.backfill = backfill;
        this.events = events;
        this.desktop = desktop;
    }

    public boolean isLocked() {
        return !encryptor.isConfigured();
    }

    /**
     * After a successful sign-in: opens the CRM with this person's password if it's locked, or gives
     * them their own locked copy of the key if they don't have one that opens with this password.
     */
    public synchronized void signedIn(String sessionId, String email, String password) {
        AppUser user = users.findByEmailIgnoreCase(email == null ? "" : email.trim()).orElse(null);
        if (user == null || password == null) {
            return;
        }

        if (isLocked()) {
            if (user.getKeyWrap() == null) {
                log.info("{} has no key on this computer yet; the CRM stays locked until the CRM key is entered",
                        user.getEmail());
                return;
            }
            try {
                encryptor.configure(KeyWrap.unwrap(user.getKeyWrap(), password));
            } catch (WrongPasswordException | IllegalStateException problem) {
                log.warn("{}'s copy of the CRM key didn't open: {}", user.getEmail(), problem.getMessage());
                return;
            }
            afterUnlock();
        } else if (!opensWith(user, password)) {
            saveKeyCopy(user, password);
        }

        if (sessionId != null) {
            unlockedSessions.add(sessionId);
        }
        events.publishEvent(new AccountCanUnlock(user.getEmail()));
    }

    /**
     * Opens the CRM with the key from the password manager, for someone whose account has no copy
     * that opens on this computer. Gives them one, so their password works from now on.
     */
    public synchronized void unlockWithKey(String sessionId, AppUser user, String key, String password) {
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadRequestException("That password isn't right");
        }
        String cleanKey = key == null ? "" : key.trim();
        if (!encryptor.isThisCrmsKey(cleanKey)) {
            throw new BadRequestException("That isn't this CRM's key. Paste the whole key, exactly as saved.");
        }
        if (!encryptor.isConfigured()) {
            encryptor.configure(cleanKey);
            afterUnlock();
        }
        saveKeyCopy(user, password);
        if (sessionId != null) {
            unlockedSessions.add(sessionId);
        }
        events.publishEvent(new AccountCanUnlock(user.getEmail()));
    }

    /** Gives an account its locked copy of the key, after its password is set or changed. */
    public void lockKeyFor(AppUser user, String password) {
        if (encryptor.isConfigured() && password != null) {
            user.setKeyWrap(KeyWrap.wrap(encryptor.exportKey(), password));
        }
    }

    /** Locks a desktop copy if nobody is signed in (for example right after setup). */
    public synchronized void lockIfNobodySignedIn() {
        if (desktop && unlockedSessions.isEmpty() && !isLocked()) {
            encryptor.lock();
            log.info("Nobody is signed in; the CRM is locked");
        }
    }

    public synchronized void sessionEnded(String sessionId) {
        if (sessionId != null && unlockedSessions.remove(sessionId)) {
            lockIfNobodySignedIn();
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        sessionEnded(event.getSession().getId());
    }

    /** Signing in again in the same browser renames the session; keep tracking it under its new name. */
    @Override
    public synchronized void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        if (unlockedSessions.remove(oldSessionId)) {
            unlockedSessions.add(event.getSession().getId());
        }
    }

    private void saveKeyCopy(AppUser user, String password) {
        user.setKeyWrap(KeyWrap.wrap(encryptor.exportKey(), password));
        users.save(user);
    }

    private boolean opensWith(AppUser user, String password) {
        if (user.getKeyWrap() == null) {
            return false;
        }
        try {
            return KeyWrap.unwrap(user.getKeyWrap(), password).equals(encryptor.exportKey());
        } catch (WrongPasswordException wrong) {
            return false;
        }
    }

    private void afterUnlock() {
        log.info("The CRM is unlocked");
        try {
            backfill.run(null);
        } catch (RuntimeException problem) {
            // Not a reason to refuse the sign-in; it runs again at the next unlock.
            log.warn("Could not finish encrypting older records", problem);
        }
    }
}
