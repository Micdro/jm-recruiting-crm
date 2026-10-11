package com.janemichael.jmrecruitingcrm.auth;

import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.EncryptionBackfill;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.KeyWrap;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrmLockTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final String EMAIL = "michael@jane-michael.test";
    private static final String PASSWORD = "a-long-password-1";

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final EncryptionBackfill backfill = mock(EncryptionBackfill.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    /** A desktop copy as it starts: it knows its sync token but not the key. */
    private final FieldEncryptor encryptor = new FieldEncryptor("", true, FieldEncryptor.syncTokenFor(KEY));
    private final CrmLock lock = new CrmLock(encryptor, users, passwordEncoder, backfill, events, true);

    private AppUser user(String keyWrap) {
        AppUser user = new AppUser();
        user.setEmail(EMAIL);
        user.setDisplayName("Michael");
        user.setRole(UserRole.ADMIN);
        user.setActive(true);
        user.setPasswordHash("{test}hash");
        user.setKeyWrap(keyWrap);
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void signingInUnlocksAndTheLastSignOutLocksAgain() {
        user(KeyWrap.wrap(KEY, PASSWORD));
        assertThat(lock.isLocked()).isTrue();

        lock.signedIn("first", EMAIL, PASSWORD);
        assertThat(lock.isLocked()).isFalse();
        verify(backfill).run(null);

        lock.signedIn("second", EMAIL, PASSWORD);
        lock.sessionEnded("first");
        assertThat(lock.isLocked()).as("still signed in in another window").isFalse();

        lock.sessionEnded("second");
        assertThat(lock.isLocked()).isTrue();
    }

    @Test
    void aSessionRenamedBySigningInAgainIsStillTracked() {
        user(KeyWrap.wrap(KEY, PASSWORD));
        lock.signedIn("before", EMAIL, PASSWORD);

        MockHttpSession renamed = new MockHttpSession(null, "after");
        lock.sessionIdChanged(new HttpSessionEvent(renamed), "before");
        lock.sessionDestroyed(new HttpSessionEvent(renamed));

        assertThat(lock.isLocked()).isTrue();
    }

    @Test
    void anAccountWithoutItsOwnCopyStaysLockedUntilTheKeyIsEntered() {
        AppUser user = user(null);
        when(passwordEncoder.matches(PASSWORD, "{test}hash")).thenReturn(true);

        lock.signedIn("session", EMAIL, PASSWORD);
        assertThat(lock.isLocked()).isTrue();

        assertThatThrownBy(() -> lock.unlockWithKey("session", user, KEY, "not-the-password"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> lock.unlockWithKey("session", user, OTHER_KEY, PASSWORD))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("isn't this CRM's key");
        assertThat(lock.isLocked()).isTrue();

        lock.unlockWithKey("session", user, " " + KEY + " ", PASSWORD);
        assertThat(lock.isLocked()).isFalse();
        verify(users).save(user);
        verify(events).publishEvent(new CrmLock.AccountCanUnlock(EMAIL));

        // From now on the password alone opens it.
        lock.sessionEnded("session");
        assertThat(lock.isLocked()).isTrue();
        lock.signedIn("next", EMAIL, PASSWORD);
        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    void signingInWhileUnlockedGivesTheAccountItsOwnCopy() throws Exception {
        encryptor.configure(KEY);
        AppUser user = user(null);

        lock.signedIn("session", EMAIL, PASSWORD);

        assertThat(KeyWrap.unwrap(user.getKeyWrap(), PASSWORD)).isEqualTo(KEY);
        verify(users).save(user);
        verify(events).publishEvent(new CrmLock.AccountCanUnlock(EMAIL));
    }

    @Test
    void anAccountWhoseCopyAlreadyOpensIsLeftAlone() {
        encryptor.configure(KEY);
        String wrapped = KeyWrap.wrap(KEY, PASSWORD);
        AppUser user = user(wrapped);

        lock.signedIn("session", EMAIL, PASSWORD);

        assertThat(user.getKeyWrap()).isEqualTo(wrapped);
        verify(users, never()).save(any());
        verify(events).publishEvent(new CrmLock.AccountCanUnlock(EMAIL));
    }

    @Test
    void aSignInThatCannotOpenTheCrmIsNotAnnounced() {
        user(KeyWrap.wrap(OTHER_KEY, PASSWORD));

        lock.signedIn("session", EMAIL, PASSWORD);

        assertThat(lock.isLocked()).isTrue();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aPasswordChangeLocksANewCopyWithTheNewPassword() throws Exception {
        AppUser user = user(null);
        lock.lockKeyFor(user, "new-long-password");
        assertThat(user.getKeyWrap()).as("nothing to copy while locked").isNull();

        encryptor.configure(KEY);
        lock.lockKeyFor(user, "new-long-password");
        assertThat(KeyWrap.unwrap(user.getKeyWrap(), "new-long-password")).isEqualTo(KEY);
    }

    @Test
    void aServerNeverLocks() {
        FieldEncryptor serverEncryptor = new FieldEncryptor(KEY);
        CrmLock server = new CrmLock(serverEncryptor, users, passwordEncoder, backfill, events, false);
        user(KeyWrap.wrap(KEY, PASSWORD));

        server.signedIn("session", EMAIL, PASSWORD);
        server.sessionEnded("session");
        server.lockIfNobodySignedIn();

        assertThat(server.isLocked()).isFalse();
    }
}
