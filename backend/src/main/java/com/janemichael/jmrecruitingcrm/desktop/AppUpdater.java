package com.janemichael.jmrecruitingcrm.desktop;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janemichael.jmrecruitingcrm.common.AppVersion;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps the Windows app up to date. Every hour it looks at the newest build on the release page;
 * when there's a newer one, it says so with a Windows notification (and a banner on every page), and
 * "Update now" downloads it, checks it, unpacks it next to the app,
 * and hands over to a small script that swaps the folders once the app has closed and starts the
 * new version. An app unzipped into OneDrive or Google Drive moves to
 * {@code %LOCALAPPDATA%\Programs\JM CRM} on its next update, since those keep its files busy. The
 * database and settings live elsewhere ({@code %LOCALAPPDATA%\JMCRM}), so they carry over untouched.
 */
@Component
public class AppUpdater {

    private static final Logger log = LoggerFactory.getLogger(AppUpdater.class);

    public static final String DEFAULT_URL =
            "https://github.com/Micdro/jm-recruiting-crm/releases/download/windows-latest/latest.json";
    static final String EXE = "JM CRM.exe";
    static final String APP_FOLDER = "JM CRM";
    /** Folders that OneDrive, Google Drive, Dropbox or iCloud keep in sync, by name. */
    private static final List<String> SYNCED_FOLDER_PREFIXES = List.of("onedrive", "dropbox");
    private static final List<String> SYNCED_FOLDER_NAMES =
            List.of("my drive", "shared drives", "google drive", "icloud drive", "iclouddrive");
    /** While an update waits to be installed, Windows reminds about it again this often. */
    static final Duration REMIND_AGAIN_AFTER = Duration.ofDays(1);

    /** What the release page says about the newest build. {@code zip} is relative to the page. */
    record Latest(int build, String sha256, String zip) {
    }

    public enum Phase { IDLE, DOWNLOADING, INSTALLING, RESTARTING, FAILED }

    /**
     * @param supported whether this copy can update itself (the installed Windows app)
     * @param version   this copy's version
     * @param latest    the newest version on the release page, once checked
     * @param available whether that one is newer than this
     * @param percent   how much of the download is done, while downloading
     * @param problem   why checking or updating didn't work, if it didn't
     */
    public record UpdateStatus(
            boolean supported,
            Integer build,
            String version,
            Integer latestBuild,
            String latest,
            boolean available,
            Phase phase,
            Integer percent,
            String problem
    ) {
    }

    /** A problem a person can act on. */
    static class UpdateException extends Exception {
        UpdateException(String message) {
            super(message);
        }
    }

    private final boolean desktop;
    private final boolean enabled;
    private final URI url;
    private final String home;
    private final ApplicationContext context;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private volatile Latest latest;
    private volatile Phase phase = Phase.IDLE;
    private volatile long downloaded;
    private volatile long total;
    private volatile String problem;

    public AppUpdater(
            @Value("${app.desktop.enabled:false}") boolean desktop,
            @Value("${app.update.enabled:true}") boolean enabled,
            @Value("${app.update.url:" + DEFAULT_URL + "}") String url,
            @Value("${app.desktop.home:}") String home,
            ApplicationContext context
    ) {
        this.desktop = desktop;
        this.enabled = enabled;
        this.url = URI.create(url.trim());
        this.home = home;
        this.context = context;
    }

    /** Only the installed Windows app updates itself; a server is updated by redeploying it. */
    public boolean supported() {
        return desktop && enabled && DesktopOs.isWindows() && DesktopOs.executable() != null
                && AppVersion.build() != null;
    }

    @Scheduled(initialDelayString = "${app.update.initial-delay-ms:60000}",
            fixedDelayString = "${app.update.interval-ms:3600000}")
    public void scheduledCheck() {
        if (supported() && (phase == Phase.IDLE || phase == Phase.FAILED)) {
            check();
            notifyIfReady();
        }
    }

    /**
     * A Windows notification when a newer version is ready: when it's first found, then once a day
     * while it waits. Clicking it opens the CRM, where the banner has Update now.
     */
    private void notifyIfReady() {
        UpdateStatus status = status();
        if (!status.available() || phase != Phase.IDLE) {
            return;
        }
        try {
            DesktopSettings settings = new DesktopSettings(home());
            Instant now = Instant.now();
            if (!notificationDue(status.latestBuild(), settings.updateNotifiedBuild(), settings.updateNotifiedAt(), now)) {
                return;
            }
            Notice notice = notice(status.latest());
            // Only counted as shown if Windows took it, so a computer without the icon yet tries again.
            if (DesktopTray.notify(notice.title(), notice.body())) {
                settings.markUpdateNotified(status.latestBuild(), now);
            }
        } catch (RuntimeException problem) {
            log.warn("Could not show the update notification", problem);
        }
    }

    record Notice(String title, String body) {
    }

    static Notice notice(String version) {
        return new Notice("JM CRM update ready",
                "Version " + version + " is ready. Open the CRM and click Update now. It takes about a minute, "
                        + "then you sign in again.");
    }

    /**
     * Whether to show the notification for {@code latestBuild}: a version not notified about yet, or
     * the same one still waiting a day after the last notification.
     */
    static boolean notificationDue(int latestBuild, Integer notifiedBuild, Instant notifiedAt, Instant now) {
        if (notifiedBuild == null || notifiedAt == null || latestBuild != notifiedBuild) {
            return true;
        }
        return !now.isBefore(notifiedAt.plus(REMIND_AGAIN_AFTER));
    }

    /** Looks at the release page for a newer version. */
    public synchronized UpdateStatus check() {
        if (!supported()) {
            return status();
        }
        try {
            latest = fetchLatest();
            if (phase == Phase.FAILED) {
                phase = Phase.IDLE;
            }
            problem = null;
        } catch (UpdateException exception) {
            problem = "Couldn't check for updates: " + exception.getMessage();
            log.info(problem);
        } catch (IOException exception) {
            problem = "Couldn't reach the release page to check for updates. It tries again within the hour.";
            log.info("Update check failed: {}", exception.toString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return status();
    }

    public UpdateStatus status() {
        Integer build = AppVersion.build();
        Latest newest = latest;
        boolean available = supported() && newest != null && build != null && newest.build() > build;
        Integer percent = phase == Phase.DOWNLOADING && total > 0 ? (int) Math.min(100, downloaded * 100 / total) : null;
        return new UpdateStatus(supported(), build, AppVersion.name(build),
                newest == null ? null : newest.build(), newest == null ? null : AppVersion.name(newest.build()),
                available, phase, percent, problem);
    }

    /** Downloads and installs the newest version, then restarts the app. Returns right away. */
    public synchronized UpdateStatus start() {
        if (!supported()) {
            throw new BadRequestException("Only the Windows app updates itself.");
        }
        if (phase == Phase.DOWNLOADING || phase == Phase.INSTALLING || phase == Phase.RESTARTING) {
            return status();
        }
        check();
        Latest target = latest;
        if (target == null) {
            throw new BadRequestException(problem == null ? "Couldn't find the newest version." : problem);
        }
        if (target.build() <= AppVersion.build()) {
            throw new BadRequestException("This computer already has the newest version.");
        }
        phase = Phase.DOWNLOADING;
        problem = null;
        downloaded = 0;
        total = 0;
        Thread worker = new Thread(() -> install(target), "app-update");
        worker.setDaemon(false);
        worker.start();
        return status();
    }

    private void install(Latest target) {
        try {
            Path appDir = Path.of(DesktopOs.executable()).toAbsolutePath().getParent();
            Path standard = DesktopPaths.installFolder().toAbsolutePath().normalize();
            boolean inStandardFolder = sameFolder(appDir, standard);
            Path parent = appDir.getParent();
            // OneDrive and Google Drive keep the app's files open while they sync them, so the folder
            // can't be swapped. An app there (or somewhere it can't write) moves to the standard folder.
            boolean move = !inStandardFolder && (inSyncedFolder(appDir.toString(), syncedRoots())
                    || parent == null || !Files.isWritable(parent));
            Path destination = move ? standard : appDir;
            Files.createDirectories(destination.getParent());
            if (!Files.isWritable(destination.getParent())) {
                throw new UpdateException("The CRM can't update itself where it's installed (" + appDir + ").");
            }
            if (move) {
                log.info("Moving the app from {} to {}, where its files aren't kept busy", appDir, destination);
            }
            Path updates = Files.createDirectories(home().resolve("updates"));
            Path zip = updates.resolve("JM-CRM-Windows-" + target.build() + ".zip");
            download(url.resolve(target.zip()), zip);
            if (!sha256(zip).equalsIgnoreCase(target.sha256())) {
                Files.deleteIfExists(zip);
                throw new UpdateException("The download was damaged, or a newer version came out meanwhile. Try again.");
            }

            phase = Phase.INSTALLING;
            // Unpacked next to where it will go, so moving it into place is a quick rename.
            Path staging = destination.getParent().resolve(destination.getFileName() + " update " + target.build());
            deleteRecursively(staging);
            extract(zip, staging);
            Path newApp = staging.resolve(APP_FOLDER);
            if (!Files.isRegularFile(newApp.resolve(EXE))) {
                throw new UpdateException("The download doesn't contain the app.");
            }
            Files.deleteIfExists(zip);

            String script = installScript(ProcessHandle.current().pid(), appDir, move, inStandardFolder ? null : standard,
                    newApp, staging, home().resolve("logs").resolve("update.log"), AppVersion.name(target.build()));
            phase = Phase.RESTARTING;
            if (!DesktopOs.runDetachedPowerShell(script)) {
                deleteRecursively(staging);
                throw new UpdateException("Windows didn't let the CRM start its updater.");
            }
            log.info("Restarting to finish updating to {}", AppVersion.name(target.build()));
            Thread.sleep(1500);
            System.exit(SpringApplication.exit(context));
        } catch (UpdateException exception) {
            fail(exception.getMessage());
        } catch (IOException exception) {
            fail("The update couldn't be downloaded or unpacked: " + exception.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("The update was interrupted.");
        } catch (RuntimeException exception) {
            log.warn("Update failed", exception);
            fail("The update failed: " + exception.getMessage());
        }
    }

    private void fail(String message) {
        problem = message;
        phase = Phase.FAILED;
        log.warn("Update failed: {}", message);
    }

    // Steps ---------------------------------------------------------------------------

    private Latest fetchLatest() throws IOException, InterruptedException, UpdateException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new UpdateException("the release page answered " + response.statusCode());
        }
        return parseLatest(response.body());
    }

    Latest parseLatest(byte[] body) throws UpdateException {
        Latest parsed;
        try {
            parsed = json.readValue(body, Latest.class);
        } catch (IOException unreadable) {
            throw new UpdateException("the release page's version information is unreadable");
        }
        if (parsed.build() <= 0 || parsed.sha256() == null || !parsed.sha256().matches("(?i)[0-9a-f]{64}")
                || parsed.zip() == null || parsed.zip().isBlank()) {
            throw new UpdateException("the release page's version information is incomplete");
        }
        return parsed;
    }

    private void download(URI source, Path target) throws IOException, InterruptedException, UpdateException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(30))
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new UpdateException("The download answered " + response.statusCode() + ". Try again later.");
        }
        total = response.headers().firstValueAsLong("Content-Length").orElse(0);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                downloaded += read;
            }
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Unpacks a zip, refusing any entry that would land outside the target folder. */
    static void extract(Path zip, Path target) throws IOException, UpdateException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                // Some Windows tools write backslashes in zip entry names.
                String name = entry.getName().replace('\\', '/');
                Path out = root.resolve(name).normalize();
                if (out.equals(root)) {
                    continue;
                }
                if (!out.startsWith(root)) {
                    throw new UpdateException("The download contains a file outside the app folder.");
                }
                if (entry.isDirectory() || name.endsWith("/")) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Run once the app has closed. Swaps the app folder for the new one, keeping the old one until the
     * swap worked. When the folder is kept busy (OneDrive or Google Drive syncing it), or {@code move}
     * says to skip it, the new version goes into {@code standard} instead and the old copy is removed.
     * It never copies over files in use: a copy that stops halfway leaves an app that can't start.
     * Then it starts the CRM, or the version that was there if nothing worked.
     *
     * @param standard where the app goes when it can't stay put, or null when it's already there
     */
    static String installScript(long pid, Path appDir, boolean move, Path standard, Path newApp, Path staging,
                                Path logFile, String version) {
        String app = quote(appDir.toString());
        return String.join("\n",
                "$ErrorActionPreference = 'Continue'",
                "$log = '" + quote(logFile.toString()) + "'",
                "function Note($text) { Add-Content -LiteralPath $log -Value ((Get-Date -Format s) + ' ' + $text) }",
                "Note 'Updating JM CRM to " + quote(version) + "'",
                "try { Wait-Process -Id " + pid + " -Timeout 180 -ErrorAction Stop } catch { }",
                "Start-Sleep -Seconds 2",
                "$app = '" + app + "'",
                "$new = '" + quote(newApp.toString()) + "'",
                "$old = '" + app + ".old'",
                "$staging = '" + quote(staging.toString()) + "'",
                "$standard = '" + (standard == null ? "" : quote(standard.toString())) + "'",
                "$installed = $null",
                move
                        ? "Note ('Moving the app out of ' + $app + ', where its files are kept busy, to ' + $standard)"
                        : String.join("\n",
                        "if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue }",
                        "for ($i = 0; $i -lt 15 -and -not $installed; $i++) {",
                        "  try {",
                        "    Move-Item -LiteralPath $app -Destination $old -ErrorAction Stop",
                        "    try {",
                        "      Move-Item -LiteralPath $new -Destination $app -ErrorAction Stop",
                        "      $installed = $app",
                        "    } catch {",
                        "      Move-Item -LiteralPath $old -Destination $app -ErrorAction SilentlyContinue",
                        "      throw",
                        "    }",
                        "  } catch {",
                        "    Note ('Waiting for the app folder to be free: ' + $_.Exception.Message)",
                        "    Start-Sleep -Seconds 2",
                        "  }",
                        "}",
                        "if (-not $installed -and $standard) { Note ('The app folder stays busy; installing in ' + $standard + ' instead') }"),
                "if (-not $installed -and $standard) {",
                "  if (Test-Path -LiteralPath $standard) { Remove-Item -LiteralPath $standard -Recurse -Force -ErrorAction SilentlyContinue }",
                "  if (Test-Path -LiteralPath $standard) {",
                "    Note ('Could not clear ' + $standard)",
                "  } else {",
                "    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $standard) | Out-Null",
                "    try {",
                "      Move-Item -LiteralPath $new -Destination $standard -ErrorAction Stop",
                "      $installed = $standard",
                "    } catch {",
                "      robocopy $new $standard /E /R:5 /W:2 /NFL /NDL /NJH /NJS /NP | Out-Null",
                "      if ($LASTEXITCODE -lt 8) { $installed = $standard } else {",
                "        Note ('Copying failed with code ' + $LASTEXITCODE)",
                "        Remove-Item -LiteralPath $standard -Recurse -Force -ErrorAction SilentlyContinue",
                "      }",
                "    }",
                "  }",
                "}",
                "if ($installed) { Note ('Updated in ' + $installed) } else {",
                "  Note 'The update failed; starting the version that was there'",
                "  $installed = $app",
                "}",
                "Start-Process -FilePath (Join-Path $installed '" + EXE + "') -ArgumentList '--background'",
                "Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue",
                "Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue",
                // The app moved: remove the copy left behind, its .exe first so a half-removed folder
                // can't be opened. If the .exe is held open, the old copy stays whole.
                "if ($installed -ne $app) {",
                "  try {",
                "    Remove-Item -LiteralPath (Join-Path $app '" + EXE + "') -Force -ErrorAction Stop",
                "    Remove-Item -LiteralPath $app -Recurse -Force -ErrorAction SilentlyContinue",
                "    Note ('Removed the old copy in ' + $app)",
                "  } catch {",
                "    Note ('Left the old copy in ' + $app + ': ' + $_.Exception.Message)",
                "  }",
                "}",
                "");
    }

    /**
     * Whether a folder is inside one that a cloud service syncs: under one of {@code syncedRoots}
     * (where Windows says OneDrive is), or with a synced folder's name in its path.
     */
    static boolean inSyncedFolder(String folder, Collection<String> syncedRoots) {
        String path = folder.replace('/', '\\').toLowerCase(Locale.ROOT);
        for (String root : syncedRoots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String prefix = root.replace('/', '\\').toLowerCase(Locale.ROOT);
            if (!prefix.endsWith("\\")) {
                prefix += "\\";
            }
            if ((path + "\\").startsWith(prefix)) {
                return true;
            }
        }
        for (String name : path.split("\\\\")) {
            String trimmed = name.trim();
            if (SYNCED_FOLDER_NAMES.contains(trimmed)
                    || SYNCED_FOLDER_PREFIXES.stream().anyMatch(trimmed::startsWith)) {
                return true;
            }
        }
        return false;
    }

    /** Where Windows says this person's OneDrive folders are. */
    private static List<String> syncedRoots() {
        return Stream.of("OneDrive", "OneDriveConsumer", "OneDriveCommercial")
                .map(System::getenv)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    static boolean sameFolder(Path one, Path other) {
        return one.toAbsolutePath().normalize().toString()
                .equalsIgnoreCase(other.toAbsolutePath().normalize().toString());
    }

    private Path home() {
        return home == null || home.isBlank() ? DesktopPaths.home() : Path.of(home);
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String quote(String value) {
        return value.replace("'", "''");
    }
}
