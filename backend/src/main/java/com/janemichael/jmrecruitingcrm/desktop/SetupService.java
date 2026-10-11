package com.janemichael.jmrecruitingcrm.desktop;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.AppUserRepository;
import com.janemichael.jmrecruitingcrm.auth.CrmLock;
import com.janemichael.jmrecruitingcrm.auth.LoginThrottle;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.EncryptionBackfill;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.KeyWrap;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.sync.SyncClient;
import com.janemichael.jmrecruitingcrm.sync.SyncClient.SyncException;
import com.janemichael.jmrecruitingcrm.sync.SyncIdentity;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.JoinResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncServer;
import com.janemichael.jmrecruitingcrm.sync.SyncService;
import com.janemichael.jmrecruitingcrm.sync.SyncService.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.nio.file.Path;

/**
 * First run on a computer: either start a new CRM (this becomes computer 1 and gets a new key) or
 * join one that already runs on another computer (copying everything from it).
 *
 * <p>Only the sync token is saved on this computer. The CRM key is shown once to be kept in a
 * password manager, and each account keeps its own copy locked with its password, so the data on
 * this computer can only be read after someone signs in.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    /**
     * @param needsSetup this desktop copy hasn't been started or joined yet
     * @param locked     set up, but nobody has unlocked it since the app started
     */
    public record Status(boolean desktop, boolean needsSetup, boolean locked, String suggestedName) {
    }

    public record StartResult(String key) {
    }

    public record JoinResult(long nodeId, String joinedThrough, String warning) {
    }

    private final boolean desktop;
    private final String home;
    private final FieldEncryptor encryptor;
    private final SyncIdentity identity;
    private final SyncService sync;
    private final SyncClient client;
    private final SyncServer server;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EncryptionBackfill backfill;
    private final CrmLock crmLock;
    private final LoginThrottle loginThrottle;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public SetupService(
            @Value("${app.desktop.enabled:false}") boolean desktop,
            @Value("${app.desktop.home:}") String home,
            FieldEncryptor encryptor,
            SyncIdentity identity,
            SyncService sync,
            SyncClient client,
            SyncServer server,
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            EncryptionBackfill backfill,
            CrmLock crmLock,
            LoginThrottle loginThrottle,
            JdbcTemplate jdbc,
            TransactionTemplate transaction
    ) {
        this.desktop = desktop;
        this.home = home;
        this.encryptor = encryptor;
        this.identity = identity;
        this.sync = sync;
        this.client = client;
        this.server = server;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.backfill = backfill;
        this.crmLock = crmLock;
        this.loginThrottle = loginThrottle;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public Status status() {
        boolean needsSetup = needsSetup();
        return new Status(desktop, needsSetup, desktop && !needsSetup && crmLock.isLocked(),
                desktop ? computerName() : null);
    }

    private boolean needsSetup() {
        return desktop && !encryptor.hasSyncToken();
    }

    /** Starts a brand-new CRM on this computer. Returns the new key, shown once to be saved. */
    public synchronized StartResult startNew(String computerName, String name, String email, String password) {
        requireSetupMode();
        String key = FieldEncryptor.newKey();
        encryptor.configure(key);

        transaction.executeWithoutResult(status -> {
            identity.become(1, clean(computerName, computerName()), true);
            AppUser admin = new AppUser();
            admin.setEmail(email.trim().toLowerCase());
            admin.setDisplayName(name.trim());
            admin.setRole(UserRole.ADMIN);
            admin.setActive(true);
            admin.setPasswordHash(passwordEncoder.encode(password));
            crmLock.lockKeyFor(admin, password);
            users.save(admin);
        });

        settings().saveSyncToken(encryptor.syncToken());
        finishSetup();
        log.info("Started a new CRM on this computer");
        return new StartResult(key);
    }

    /** Joins the CRM running on another computer and copies everything from it. */
    public synchronized JoinResult join(String rawAddress, String key, String computerName) {
        requireSetupMode();
        String address = SyncClient.normalizeAddress(rawAddress, sync.syncPort());
        if (address == null) {
            throw new BadRequestException("Enter the other computer's name or address");
        }
        String token;
        try {
            token = FieldEncryptor.syncTokenFor(key.trim());
        } catch (IllegalStateException invalid) {
            throw new BadRequestException("That isn't a CRM key. Paste the whole key, exactly as saved.");
        }

        JoinResponse joined;
        try {
            joined = client.join(address, token, clean(computerName, computerName()), sync.syncPort());
        } catch (SyncException problem) {
            throw new BadRequestException(problem.getMessage());
        }

        encryptor.configure(key.trim());
        identity.become((int) joined.nodeId(), clean(computerName, computerName()), false);
        settings().saveSyncToken(token);

        Peer peer = new Peer(joined.node().id(), joined.node().name(), address);
        String problem = sync.syncWith(peer);
        // Make sure this computer knows where to reach the one it joined, even if that one doesn't.
        jdbc.update("""
                INSERT INTO sync_node (id, name, address) VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET address = EXCLUDED.address
                WHERE sync_node.address IS DISTINCT FROM EXCLUDED.address
                """, peer.id(), peer.name(), address);

        finishSetup();
        log.info("Joined the CRM through {} as computer {}", peer.name(), joined.nodeId());
        return new JoinResult(joined.nodeId(), peer.name(),
                problem == null ? null : "Joined, but the first copy didn't finish: " + problem
                        + " It will keep trying in the background.");
    }

    /**
     * A forgotten password: the CRM key from the password manager sets a new one. Whoever has the key
     * can read everything anyway, so it is allowed to do this; nobody else is.
     */
    public synchronized void resetPasswordWithKey(String email, String key, String newPassword) {
        if (!desktop || !encryptor.hasSyncToken()) {
            throw new NotFoundException("Not found");
        }
        String cleanKey = key == null ? "" : key.trim();
        if (!encryptor.isThisCrmsKey(cleanKey)) {
            throw new BadRequestException("That isn't this CRM's key. Paste the whole key, exactly as saved.");
        }
        AppUser user = users.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .filter(AppUser::isActive)
                .orElseThrow(() -> new BadRequestException("There's no active account with that email"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setKeyWrap(KeyWrap.wrap(cleanKey, newPassword));
        users.save(user);
        loginThrottle.recordSuccess(user.getEmail());
        log.info("Set a new password for {} with the CRM key", user.getEmail());
    }

    /** Opens the sync port, then locks the CRM until someone signs in. */
    private void finishSetup() {
        server.open();
        backfill.run(null);
        crmLock.lockIfNobodySignedIn();
        DesktopOs.setStartsWithWindows(true);
        DesktopOs.allowSyncThroughFirewall(sync.syncPort());
    }

    private void requireSetupMode() {
        if (!desktop) {
            throw new NotFoundException("Not found");
        }
        if (encryptor.hasSyncToken()) {
            throw new ConflictException("This computer is already set up.");
        }
    }

    /**
     * Older versions kept the CRM key in settings.properties. Once someone has signed in and their
     * own locked copy is saved, the key is no longer needed on disk.
     */
    @EventListener
    public void accountCanUnlock(CrmLock.AccountCanUnlock event) {
        if (!desktop) {
            return;
        }
        DesktopSettings settings = settings();
        if (settings.legacyEncryptionKey() != null) {
            settings.forgetLegacyEncryptionKey();
            log.info("Removed the CRM key from settings.properties; signing in unlocks the CRM from now on");
        }
    }

    private DesktopSettings settings() {
        return new DesktopSettings(home == null || home.isBlank() ? DesktopPaths.home() : Path.of(home));
    }

    static String computerName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (Exception unknown) {
                name = "This computer";
            }
        }
        return name;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }
}
