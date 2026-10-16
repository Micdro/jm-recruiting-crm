package com.janemichael.jmrecruitingcrm.desktop;

import com.janemichael.jmrecruitingcrm.JmRecruitingCrmApplication;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.SplashScreen;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;

/**
 * Runs the whole CRM on one computer: its own PostgreSQL database in the user's app-data folder,
 * the app on http://localhost:4780, and the sync port for the other computers. Double-clicking the
 * app again while it runs just opens the browser.
 */
public final class DesktopLauncher {

    private static final Logger log = LoggerFactory.getLogger(DesktopLauncher.class);
    private static final String BACKGROUND = "--background";

    /** Held for as long as the app runs, so a second copy can tell one is already starting. */
    private static FileLock instanceLock;
    /** Found before Spring starts (Spring switches AWT to headless, after which it can't be looked up). */
    private static SplashScreen splash;

    private DesktopLauncher() {
    }

    public static boolean requested() {
        return "true".equalsIgnoreCase(DesktopPaths.setting("jmcrm.desktop", "JMCRM_DESKTOP"));
    }

    public static void run(String[] args) throws Exception {
        boolean background = Arrays.asList(args).contains(BACKGROUND);
        String[] springArgs = Arrays.stream(args).filter(arg -> !BACKGROUND.equals(arg)).toArray(String[]::new);
        boolean openBrowser = !background && DesktopPaths.setting("jmcrm.no-browser", "JMCRM_NO_BROWSER") == null;

        Path home = DesktopPaths.home();
        Files.createDirectories(home);
        int uiPort = DesktopPaths.port("jmcrm.port", "JMCRM_PORT", DesktopPaths.UI_PORT);
        int syncPort = DesktopPaths.port("jmcrm.sync-port", "JMCRM_SYNC_PORT", DesktopPaths.SYNC_PORT);
        int databasePort = DesktopPaths.port("jmcrm.db-port", "JMCRM_DB_PORT", DesktopPaths.DATABASE_PORT);
        String url = "http://localhost:" + uiPort + "/";

        splash = findSplash();
        if (background) {
            closeSplash();
        }

        // Opened again while it runs, or while it's still starting: wait for it and open the browser.
        instanceLock = FileChannel.open(home.resolve("app.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE).tryLock();
        if (instanceLock == null || alreadyRunning(uiPort)) {
            if (openBrowser) {
                if (waitUntilRunning(uiPort, Duration.ofMinutes(3))) {
                    DesktopOs.openBrowser(url);
                } else {
                    // The other copy holds on but never answers: say so rather than vanish.
                    closeSplash();
                    DesktopStartupError.notResponding();
                }
            }
            closeSplash();
            return;
        }

        EmbeddedPostgres database = startDatabase(home, databasePort);
        ConfigurableApplicationContext[] started = new ConfigurableApplicationContext[1];
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DesktopTray.remove();
            if (started[0] != null) {
                started[0].close();
            }
            stopDatabase(database);
        }, "desktop-shutdown"));
        createDatabaseIfMissing(databasePort);

        DesktopSettings settings = new DesktopSettings(home);
        System.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:" + databasePort + "/jmcrm");
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "");
        System.setProperty("server.address", "127.0.0.1");
        System.setProperty("server.port", String.valueOf(uiPort));
        System.setProperty("app.desktop.enabled", "true");
        System.setProperty("app.desktop.home", home.toString());
        System.setProperty("app.sync.port", String.valueOf(syncPort));
        System.setProperty("logging.file.name", home.resolve("logs").resolve("jm-crm.log").toString());
        // Only the sync token is kept on disk. The app starts locked and is unlocked by signing in.
        String legacyKey = settings.legacyEncryptionKey();
        if (settings.syncToken() == null && legacyKey != null) {
            settings.saveSyncToken(FieldEncryptor.syncTokenFor(legacyKey));
        }
        if (settings.syncToken() != null) {
            System.setProperty("app.sync.token", settings.syncToken());
        }
        if (legacyKey != null) {
            // Set up by an older version, which kept the key on disk. Start unlocked this once, so each
            // person's sign-in can save their own locked copy; then the key is removed from the file.
            System.setProperty("app.encryption.key", legacyKey);
        }

        SpringApplication application = new SpringApplication(JmRecruitingCrmApplication.class);
        application.setRegisterShutdownHook(false);
        // Windows: the icon by the clock and its notifications need AWT, which Spring otherwise turns off.
        application.setHeadless(!DesktopOs.isWindows());
        started[0] = application.run(springArgs);
        Thread tray = new Thread(() -> DesktopTray.install(url), "desktop-tray");
        tray.setDaemon(true);
        tray.start();

        // Shortcuts follow the app: made on the first start, and pointed at its new place if it moved.
        String exe = DesktopOs.executable();
        if (exe != null && !exe.equalsIgnoreCase(settings.shortcutsTarget())) {
            if (settings.shortcutsCreated()) {
                DesktopOs.repointShortcuts();
            } else {
                DesktopOs.createShortcuts();
            }
            settings.saveShortcutsTarget(exe);
        }
        if (openBrowser) {
            DesktopOs.openBrowser(url);
        }
        closeSplash();
        log.info("JM CRM is running at {}", url);
    }

    /** The "starting" picture shown while the app loads (the Windows app is packaged with one). */
    private static SplashScreen findSplash() {
        try {
            return SplashScreen.getSplashScreen();
        } catch (RuntimeException | LinkageError noDisplay) {
            return null;
        }
    }

    private static void closeSplash() {
        try {
            if (splash != null && splash.isVisible()) {
                splash.close();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Already gone.
        }
    }

    private static boolean waitUntilRunning(int port, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (alreadyRunning(port)) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    /**
     * Starts PostgreSQL from the files bundled with the app. If a database from an earlier run is
     * still up (the app was closed abruptly), it is used as is.
     */
    private static EmbeddedPostgres startDatabase(Path home, int port) throws IOException {
        Path data = home.resolve("database");
        if (portOpen(port) && Files.exists(data.resolve("postmaster.pid"))) {
            log.info("Using the database that is already running on port {}", port);
            return null;
        }
        Files.createDirectories(home.resolve("postgres"));
        Path logs = Files.createDirectories(home.resolve("logs"));
        if (!Files.exists(data)) {
            DesktopOs.grantCurrentUserFullControl(home);
        }
        ProcessBuilder.Redirect databaseLog = ProcessBuilder.Redirect.appendTo(logs.resolve("database.log").toFile());
        return EmbeddedPostgres.builder()
                .setOutputRedirector(databaseLog)
                .setErrorRedirector(databaseLog)
                .setDataDirectory(data)
                .setCleanDataDirectory(false)
                .setOverrideWorkingDirectory(home.resolve("postgres").toFile())
                .setPort(port)
                .setRegisterShutdownHook(false)
                .setPGStartupWait(Duration.ofSeconds(90))
                .setServerConfig("synchronous_commit", "on")
                .start();
    }

    private static void stopDatabase(EmbeddedPostgres database) {
        if (database == null) {
            return;
        }
        try {
            database.close();
        } catch (IOException exception) {
            log.warn("Could not stop the database cleanly", exception);
        }
    }

    private static void createDatabaseIfMissing(int port) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + port + "/postgres", "postgres", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet existing = statement.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'jmcrm'")) {
                if (existing.next()) {
                    return;
                }
            }
            statement.execute("CREATE DATABASE jmcrm");
        }
    }

    private static boolean alreadyRunning(int port) {
        if (!portOpen(port)) {
            return false;
        }
        try {
            HttpResponse<Void> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                            .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
