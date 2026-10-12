package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.sync.SyncClient.SyncException;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.ChangesResponse;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.NodeInfo;
import com.janemichael.jmrecruitingcrm.sync.SyncProtocol.PushResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sync port and the client, talking over a real connection on this computer. */
class SyncServerTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final NodeInfo OFFICE = new NodeInfo(1, "Office PC");
    private static final NodeInfo LAPTOP = new NodeInfo(2, "Laptop");
    private static final SyncChange ACME = new SyncChange("company", 2_000_000_001L, 1_760_000_000_000_000L, 2, false,
            Map.of("id", "2000000001", "name", "Acme AI", "status", "LEAD"));

    private final SyncService sync = mock(SyncService.class);
    private final SyncApplier applier = mock(SyncApplier.class);
    private final SyncIdentity identity = mock(SyncIdentity.class);
    private final FieldEncryptor encryptor = new FieldEncryptor(KEY);
    private final SyncServer server = new SyncServer(sync, applier, identity, encryptor, true, 0, "127.0.0.1");
    private final SyncClient client = new SyncClient();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void open() {
        when(identity.isEnabled()).thenReturn(true);
        when(sync.self()).thenReturn(OFFICE);
        assertThat(server.open()).isTrue();
    }

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void changesTravelSignedAndSealed() throws Exception {
        when(sync.changesSince(5, 2)).thenReturn(new ChangesResponse(SyncProtocol.VERSION, OFFICE, 9, List.of(ACME)));

        ChangesResponse response = client.changes(address(), 5, cipher(KEY), LAPTOP);

        assertThat(response.node()).isEqualTo(OFFICE);
        assertThat(response.cursor()).isEqualTo(9);
        assertThat(response.changes()).containsExactly(ACME);
    }

    @Test
    void changesSentToAComputerThatCantConnectBackAreApplied() throws Exception {
        when(applier.apply(anyList())).thenReturn(new SyncApplier.Result(1, 0, 0, 0));

        PushResponse response = client.push(address(), List.of(ACME), cipher(KEY), LAPTOP);

        assertThat(response.applied()).isEqualTo(1);
        assertThat(response.node()).isEqualTo(OFFICE);
        verify(applier).apply(List.of(ACME));
    }

    @Test
    void anotherCrmsKeyIsRefused() {
        assertThatThrownBy(() -> client.changes(address(), 0, cipher(OTHER_KEY), LAPTOP))
                .isInstanceOf(SyncException.class)
                .hasMessageContaining("different CRM key");
        assertThatThrownBy(() -> client.push(address(), List.of(ACME), cipher(OTHER_KEY), LAPTOP))
                .isInstanceOf(SyncException.class);
        verify(sync, never()).changesSince(anyLong(), anyLong());
        verify(applier, never()).apply(anyList());
    }

    @Test
    void unsignedOrOldRequestsGetNothing() throws Exception {
        HttpResponse<String> unsigned = http.send(HttpRequest.newBuilder(uri("/api/sync/changes?since=0")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unsigned.statusCode()).isEqualTo(401);

        // Correctly signed, but an hour old: refused, so a recorded request can't be replayed later.
        long anHourAgo = System.currentTimeMillis() - 60 * 60 * 1000L;
        String path = "/api/sync/changes?since=0";
        String signature = cipher(KEY).sign(SyncProtocol.signed("GET", path, 2, anHourAgo, new byte[0]));
        HttpResponse<String> old = http.send(HttpRequest.newBuilder(uri(path))
                        .header(SyncProtocol.NODE_HEADER, "2")
                        .header(SyncProtocol.TIME_HEADER, String.valueOf(anHourAgo))
                        .header(SyncProtocol.SIGNATURE_HEADER, signature)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(old.statusCode()).isEqualTo(401);
        verify(sync, never()).changesSince(anyLong(), anyLong());
    }

    @Test
    void anAddressThatKeepsFailingIsShutOutForAWhile() throws Exception {
        HttpRequest unsigned = HttpRequest.newBuilder(uri("/api/sync/changes?since=0")).build();
        for (int attempt = 0; attempt < 20; attempt++) {
            http.send(unsigned, HttpResponse.BodyHandlers.discarding());
        }

        assertThat(http.send(unsigned, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(429);
        assertThatThrownBy(() -> client.changes(address(), 0, cipher(KEY), LAPTOP)).isInstanceOf(SyncException.class);
    }

    @Test
    void aComputerThatIsNotSetUpSaysSo() {
        when(identity.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> client.changes(address(), 0, cipher(KEY), LAPTOP))
                .isInstanceOf(SyncException.class)
                .hasMessageContaining("isn't set up yet");
    }

    private String address() {
        return "127.0.0.1:" + server.port();
    }

    private URI uri(String path) {
        return URI.create("http://" + address() + path);
    }

    private static SyncCipher cipher(String key) {
        return SyncCipher.forDirect(FieldEncryptor.syncTokenFor(key));
    }
}
