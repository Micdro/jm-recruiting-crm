package com.janemichael.jmrecruitingcrm.desktop;

import com.janemichael.jmrecruitingcrm.JmRecruitingCrmApplication;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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

        if (alreadyRunning(uiPort)) {
            if (openBrowser) {
                DesktopOs.openBrowser(url);
            }
            return;
        }

        EmbeddedPostgres database = startDatabase(home, databasePort);
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
        if (settings.encryptionKey() != null) {
            System.setProperty("app.encryption.key", settings.encryptionKey());
        }

        SpringApplication application = new SpringApplication(JmRecruitingCrmApplication.class);
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(springArgs);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            context.close();
            stopDatabase(database);
        }, "desktop-shutdown"));

        if (!settings.shortcutsCreated()) {
            DesktopOs.createShortcuts();
            settings.markShortcutsCreated();
        }
        if (openBrowser) {
            DesktopOs.openBrowser(url);
        }
        log.info("JM CRM is running at {}", url);
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
