package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.sync.Backups.Entry;
import com.janemichael.jmrecruitingcrm.sync.Backups.Restored;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** This computer's backups, on the This computer page. Restoring is the owner's. */
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    /**
     * @param available  false on a server, which uses its database host's backups
     * @param folder     where this computer keeps them
     * @param canRestore whether the signed-in person may restore (the account owner)
     * @param ownerName  who can, to say so
     */
    public record Status(boolean available, String folder, List<Entry> backups, boolean canRestore, String ownerName,
                         int keep, int everyDays) {
    }

    private final Backups backups;
    private final CurrentUserService currentUser;

    public BackupController(Backups backups, CurrentUserService currentUser) {
        this.backups = backups;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Status status() throws IOException {
        AppUser me = currentUser.get();
        return new Status(
                backups.isAvailable(),
                backups.isAvailable() ? backups.folder().toAbsolutePath().toString() : null,
                backups.list(),
                currentUser.isAccountOwnerOrStandIn(me),
                currentUser.accountOwnerName(),
                Backups.KEEP,
                (int) Backups.EVERY.toDays());
    }

    /** Backs up now. Anyone signed in can: it only adds a file. */
    @PostMapping
    public Status backUpNow() throws IOException {
        currentUser.get();
        backups.backUp(Backups.Kind.MANUAL);
        return status();
    }

    /** The backup file itself, to keep a copy somewhere else (it stays encrypted). */
    @GetMapping("/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        currentUser.get();
        Path file = backups.file(name);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new FileSystemResource(file));
    }

    @PostMapping("/{name}/restore")
    public Restored restore(@PathVariable String name) throws IOException {
        currentUser.requireAccountOwner("restore a backup");
        return backups.restore(name);
    }
}
