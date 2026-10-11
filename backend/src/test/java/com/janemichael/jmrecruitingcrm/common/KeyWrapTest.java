package com.janemichael.jmrecruitingcrm.common;

import com.janemichael.jmrecruitingcrm.common.KeyWrap.WrongPasswordException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyWrapTest {

    private static final String KEY = FieldEncryptorTest.TEST_KEY;
    private static final String PASSWORD = "correct horse battery";

    @Test
    void theRightPasswordOpensTheKeyAndTheStoredCopyDoesNotReveal() throws Exception {
        String wrapped = KeyWrap.wrap(KEY, PASSWORD);

        assertThat(wrapped).startsWith("kw1:").doesNotContain(KEY.substring(0, 20));
        assertThat(KeyWrap.unwrap(wrapped, PASSWORD)).isEqualTo(KEY);
    }

    @Test
    void eachCopyIsDifferent() {
        assertThat(KeyWrap.wrap(KEY, PASSWORD)).isNotEqualTo(KeyWrap.wrap(KEY, PASSWORD));
    }

    @Test
    void aWrongPasswordOrATamperedCopyDoesNotOpen() {
        String wrapped = KeyWrap.wrap(KEY, PASSWORD);

        assertThatThrownBy(() -> KeyWrap.unwrap(wrapped, "Correct horse battery"))
                .isInstanceOf(WrongPasswordException.class);

        byte[] data = Base64.getDecoder().decode(wrapped.substring("kw1:".length()));
        data[data.length - 1] ^= 1;
        String tampered = "kw1:" + Base64.getEncoder().encodeToString(data);
        assertThatThrownBy(() -> KeyWrap.unwrap(tampered, PASSWORD)).isInstanceOf(WrongPasswordException.class);

        assertThatThrownBy(() -> KeyWrap.unwrap(null, PASSWORD)).isInstanceOf(WrongPasswordException.class);
        assertThatThrownBy(() -> KeyWrap.unwrap("kw1:not base64!", PASSWORD)).isInstanceOf(WrongPasswordException.class);
        assertThatThrownBy(() -> KeyWrap.unwrap("kw1:AAAA", PASSWORD)).isInstanceOf(WrongPasswordException.class);
    }
}
