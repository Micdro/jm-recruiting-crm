package com.janemichael.jmrecruitingcrm.desktop;

import com.janemichael.jmrecruitingcrm.desktop.AppUpdater.UpdateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppUpdaterTest {

    @TempDir
    Path folder;

    private final AppUpdater updater = new AppUpdater(false, true, AppUpdater.DEFAULT_URL, "", null);

    @Test
    void readsTheNewestVersionFromTheReleasePage() throws Exception {
        String sha = "a".repeat(64);
        AppUpdater.Latest latest = updater.parseLatest(("{\"build\": 61, \"version\": \"1.0.61\", \"sha256\": \"" + sha
                + "\", \"zip\": \"JM-CRM-Windows.zip\"}").getBytes(StandardCharsets.UTF_8));

        assertThat(latest.build()).isEqualTo(61);
        assertThat(latest.sha256()).isEqualTo(sha);
        assertThat(latest.zip()).isEqualTo("JM-CRM-Windows.zip");
    }

    @Test
    void incompleteOrUnreadableVersionInformationIsRefused() {
        assertThatThrownBy(() -> updater.parseLatest("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UpdateException.class);
        assertThatThrownBy(() -> updater.parseLatest("{\"build\": 61, \"sha256\": \"short\", \"zip\": \"x.zip\"}"
                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UpdateException.class);
        assertThatThrownBy(() -> updater.parseLatest(("{\"sha256\": \"" + "a".repeat(64) + "\", \"zip\": \"x.zip\"}")
                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UpdateException.class);
    }

    @Test
    void unpacksTheAppFolderIncludingWindowsStyleNames() throws Exception {
        Path zip = zip("JM CRM/JM CRM.exe", "JM CRM\\app\\app.jar", "JM CRM/runtime/");
        Path target = folder.resolve("JM CRM update 61");

        AppUpdater.extract(zip, target);

        assertThat(target.resolve("JM CRM").resolve("JM CRM.exe")).isRegularFile();
        assertThat(target.resolve("JM CRM").resolve("app").resolve("app.jar")).isRegularFile();
        assertThat(target.resolve("JM CRM").resolve("runtime")).isDirectory();
    }

    @Test
    void refusesFilesThatWouldLandOutsideTheAppFolder() throws Exception {
        Path zip = zip("JM CRM/JM CRM.exe", "../../Startup/evil.exe");

        assertThatThrownBy(() -> AppUpdater.extract(zip, folder.resolve("update")))
                .isInstanceOf(UpdateException.class);
        assertThat(folder.resolve("Startup")).doesNotExist();
    }

    @Test
    void checksumsMatchWhatShaSumPrints() throws Exception {
        Path file = Files.writeString(folder.resolve("abc.txt"), "abc");

        assertThat(AppUpdater.sha256(file))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void theInstallScriptSwapsTheFoldersSafelyAndRestartsTheApp() {
        Path app = Path.of("C:\\Users\\Mike O'Neil\\Documents\\JM CRM\\JM CRM");
        String script = AppUpdater.installScript(4242, app, Path.of("C:\\Users\\Mike O'Neil\\Documents\\JM CRM\\JM CRM update 61\\JM CRM"),
                Path.of("C:\\Users\\Mike O'Neil\\Documents\\JM CRM\\JM CRM update 61"),
                Path.of("C:\\Users\\Mike O'Neil\\AppData\\Local\\JMCRM\\logs\\update.log"), "1.0.61");

        assertThat(script)
                .contains("Wait-Process -Id 4242")
                // An apostrophe in the path is doubled, so it can't end the PowerShell string early.
                .contains("$app = 'C:\\Users\\Mike O''Neil\\Documents\\JM CRM\\JM CRM'")
                .contains("Move-Item -LiteralPath $app -Destination $old")
                .contains("robocopy $new $app /MIR")
                .contains("Start-Process -FilePath (Join-Path $app 'JM CRM.exe') -ArgumentList '--background'")
                .contains("Note 'Updating JM CRM to 1.0.61'");
    }

    @Test
    void onlyTheInstalledWindowsAppUpdatesItself() {
        assertThat(updater.supported()).isFalse();
        assertThat(updater.status().available()).isFalse();
        assertThat(updater.status().phase()).isEqualTo(AppUpdater.Phase.IDLE);
    }

    private Path zip(String... names) throws IOException {
        Path zip = folder.resolve("update-" + names.length + "-" + System.nanoTime() + ".zip");
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(file)) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    out.write(("contents of " + name).getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return zip;
    }
}
