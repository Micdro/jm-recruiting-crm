package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.sync.FolderCipher.UnreadableException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolderCipherTest {

    private final FolderCipher cipher = FolderCipher.forToken("this-crms-sync-token");

    @Test
    void sealedFilesOpenOnlyUnderTheirOwnNameAndToken() throws Exception {
        byte[] plain = "{\"name\":\"Dana Reyes\"}".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = cipher.seal("computer-1/00000000000000000001.jmcrm", plain);

        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("Dana");
        assertThat(cipher.open("computer-1/00000000000000000001.jmcrm", sealed)).isEqualTo(plain);

        assertThatThrownBy(() -> cipher.open("computer-2/00000000000000000001.jmcrm", sealed))
                .isInstanceOf(UnreadableException.class);
        assertThatThrownBy(() -> FolderCipher.forToken("another-crms-token")
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
}
