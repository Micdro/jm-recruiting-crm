package com.janemichael.jmrecruitingcrm.desktop;

import com.janemichael.jmrecruitingcrm.auth.CrmLock;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.auth.UserRole;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ForbiddenException;
import com.janemichael.jmrecruitingcrm.sync.DirectSync;
import com.janemichael.jmrecruitingcrm.sync.FolderSync;
import com.janemichael.jmrecruitingcrm.sync.SyncServer;
import com.janemichael.jmrecruitingcrm.sync.SyncIdentity;
import com.janemichael.jmrecruitingcrm.sync.SyncService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "This computer" page (sync, updates, start with Windows, the morning reminder, the firewall and
 * quitting) and unlocking.
 */
@RestController
@RequestMapping("/api/desktop")
@ConditionalOnProperty(name = "app.desktop.enabled", havingValue = "true")
public class DesktopController {

    public record Status(
            String computerName,
            Integer nodeId,
            boolean windows,
            boolean startsWithWindows,
            SyncService.Status sync,
            FolderSync.FolderStatus folder,
            DirectSync.DirectStatus direct,
            AppUpdater.UpdateStatus update,
            MorningReminder.ReminderStatus reminder
    ) {
    }

    public record ReminderRequest(boolean enabled, String time) {
    }

    public record DirectRequest(boolean enabled) {
    }

    public record FolderRequest(
            @NotBlank(message = "Choose the sync folder")
            @Size(max = 500, message = "That folder path is too long")
            String path
    ) {
    }

    public record StartupRequest(boolean enabled) {
    }

    public record FirewallStatus(boolean allowed) {
    }

    public record UnlockRequest(
            @NotBlank(message = "Paste the CRM key")
            @Size(max = 200, message = "That key is too long")
            String key,

            @NotBlank(message = "Enter your password")
            @Size(max = 200, message = "That password is too long")
            String password
    ) {
    }

    private final SyncService sync;
    private final SyncIdentity identity;
    private final CurrentUserService currentUser;
    private final JdbcTemplate jdbc;
    private final ApplicationContext context;
    private final CrmLock crmLock;
    private final FolderSync folderSync;
    private final DirectSync direct;
    private final SyncServer syncServer;
    private final AppUpdater updater;
    private final MorningReminder reminder;

    public DesktopController(
            SyncService sync,
            FolderSync folderSync,
            DirectSync direct,
            SyncServer syncServer,
            AppUpdater updater,
            MorningReminder reminder,
            SyncIdentity identity,
            CurrentUserService currentUser,
            JdbcTemplate jdbc,
            ApplicationContext context,
            CrmLock crmLock
    ) {
        this.sync = sync;
        this.folderSync = folderSync;
        this.direct = direct;
        this.syncServer = syncServer;
        this.updater = updater;
        this.reminder = reminder;
        this.identity = identity;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.context = context;
        this.crmLock = crmLock;
    }

    /**
     * For someone signed in whose account has no copy of the key that opens on this computer yet:
     * the CRM key from the password manager unlocks it once, and their password does from then on.
     */
    @PostMapping("/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@Valid @RequestBody UnlockRequest request, HttpServletRequest http) {
        crmLock.unlockWithKey(http.getSession().getId(), currentUser.get(), request.key(), request.password());
    }

    @GetMapping("/status")
    public Status status() {
        SyncService.Status syncStatus = sync.status();
        return new Status(syncStatus.name(), syncStatus.nodeId(), DesktopOs.isWindows(),
                DesktopOs.startsWithWindows(), syncStatus, folderSync.status(), direct.status(),
                updater.status(), reminder.status());
    }

    @GetMapping("/update")
    public AppUpdater.UpdateStatus update() {
        return updater.status();
    }

    /** Looks for a newer version now instead of waiting for the next check. */
    @PostMapping("/update/check")
    public AppUpdater.UpdateStatus checkForUpdate() {
        return updater.check();
    }

    /** Downloads and installs the newest version, then restarts the CRM (it starts locked). */
    @PostMapping("/update")
    public AppUpdater.UpdateStatus installUpdate() {
        return updater.start();
    }

    /** Syncs now instead of waiting a few seconds. */
    @PostMapping("/sync-now")
    public Status syncNow() {
        folderSync.syncNow();
        sync.retryNow();
        sync.syncAll();
        return status();
    }

    /**
     * Turns direct connections on or off for this computer. Turning on asks Windows to let the other
     * computers in (with its permission prompt) and tells them, through the folder, where to connect.
     */
    @PutMapping("/direct")
    public Status setDirect(@RequestBody DirectRequest request) {
        direct.setEnabled(request.enabled());
        if (request.enabled()) {
            DesktopOs.allowSyncThroughFirewall(syncServer.port());
        }
        folderSync.syncNow();
        return status();
    }

    /** Chooses (or changes) the shared folder this computer syncs through. */
    @PutMapping("/folder")
    public Status useFolder(@Valid @RequestBody FolderRequest request) {
        folderSync.useFolder(request.path());
        return status();
    }

    @PutMapping("/startup")
    public Status setStartup(@RequestBody StartupRequest request) {
        DesktopOs.setStartsWithWindows(request.enabled());
        return status();
    }

    /** Turns the morning reminder on or off and sets its time ("08:30"). */
    @PutMapping("/reminder")
    public Status setReminder(@RequestBody ReminderRequest request) {
        reminder.update(request.enabled(), request.time());
        return status();
    }

    /** Shows the signed-in person's reminder now, to see what it looks like. */
    @PostMapping("/reminder/show")
    public MorningReminder.Shown showReminder() {
        return reminder.showNow(currentUser.get().getEmail());
    }

    @GetMapping("/firewall")
    public FirewallStatus firewall() {
        return new FirewallStatus(DesktopOs.firewallAllowsSync());
    }

    @PostMapping("/firewall")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void allowThroughFirewall() {
        DesktopOs.allowSyncThroughFirewall(syncServer.port());
    }

    /** Removes a computer that is no longer used, so the others stop trying to reach it. */
    @DeleteMapping("/computers/{id}")
    public Status removeComputer(@PathVariable long id) {
        if (currentUser.get().getRole() != UserRole.ADMIN) {
            throw new ForbiddenException("Only an admin can remove a computer");
        }
        if (id == identity.nodeId()) {
            throw new BadRequestException("This is the computer you're using");
        }
        jdbc.update("DELETE FROM sync_node WHERE id = ?", id);
        jdbc.update("DELETE FROM sync_peer_state WHERE node_id = ?", id);
        return status();
    }

    /** Closes the app. Sync stops until it is opened again. */
    @PostMapping("/quit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void quit() {
        Thread quitter = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(context));
        }, "desktop-quit");
        quitter.setDaemon(false);
        quitter.start();
    }
}
