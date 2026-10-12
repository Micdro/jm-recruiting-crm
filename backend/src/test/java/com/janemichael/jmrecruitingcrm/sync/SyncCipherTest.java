package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.sync.SyncCipher.UnreadableException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncCipherTest {

    private final SyncCipher cipher = SyncCipher.forFolder("this-crms-sync-token");

    @Test
    void sealedFilesOpenOnlyUnderTheirOwnNameAndToken() throws Exception {
        byte[] plain = "{\"name\":\"Dana Reyes\"}".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = cipher.seal("computer-1/00000000000000000001.jmcrm", plain);

        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("Dana");
        assertThat(cipher.open("computer-1/00000000000000000001.jmcrm", sealed)).isEqualTo(plain);

        assertThatThrownBy(() -> cipher.open("computer-2/00000000000000000001.jmcrm", sealed))
                .isInstanceOf(UnreadableException.class);
        assertThatThrownBy(() -> SyncCipher.forFolder("another-crms-token")
                .open("computer-1/00000000000000000001.jmcrm", sealed))
                .isInstanceOf(UnreadableException.class);
    }

    @Test
    void halfCopiedOrForeignFilesAreUnreadableRatherThanErrors() {
        byte[] sealed = cipher.seal("crm.check", new byte[100]);

        assertThatThrownBy(() -> cipher.open("crm.check", Arrays.copyOf(sealed, sealed.length - 10)))
                .isInstanceOf(UnreadableException.class);
        assertThatThrownBy(() -> cipher.open("crm.check", new byte[3])).isInstanceOf(UnreadableException.class);
        assertThatThrownBy(() -> cipher.open("crm.check", "hello, this is not ours".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UnreadableException.class);
    }

    @Test
    void folderFilesAndDirectConnectionsUseDifferentKeys() {
        byte[] sealed = cipher.seal("name", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> SyncCipher.forDirect("this-crms-sync-token").open("name", sealed))
                .isInstanceOf(UnreadableException.class);
    }

    @Test
    void signaturesProveTheTokenWithoutRevealingIt() {
        SyncCipher direct = SyncCipher.forDirect("this-crms-sync-token");
        String message = "GET\n/api/sync/changes?since=5\n2\n1760000000000\n" + SyncCipher.digest(new byte[0]);
        String signature = direct.sign(message);

        assertThat(signature).doesNotContain("this-crms-sync-token");
        assertThat(direct.verify(message, signature)).isTrue();
        assertThat(direct.verify(message.replace("since=5", "since=0"), signature)).isFalse();
        assertThat(SyncCipher.forDirect("another-crms-token").verify(message, signature)).isFalse();
        assertThat(direct.verify(message, null)).isFalse();
    }
}
