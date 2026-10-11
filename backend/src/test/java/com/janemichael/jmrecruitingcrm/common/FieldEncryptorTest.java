package com.janemichael.jmrecruitingcrm.common;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptorTest {

    static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    private final FieldEncryptor encryptor = new FieldEncryptor(TEST_KEY);

    @Test
    void encryptsAndDecryptsWithoutStoringThePlaintext() {
        String stored = encryptor.encrypt("priya@acme.ai, base $240k, wants remote");

        assertThat(stored).startsWith("enc:v1:").doesNotContain("priya").doesNotContain("240");
        assertThat(FieldEncryptor.isEncrypted(stored)).isTrue();
        assertThat(encryptor.decrypt(stored)).isEqualTo("priya@acme.ai, base $240k, wants remote");
    }

    @Test
    void theSameValueEncryptsDifferentlyEachTime() {
        assertThat(encryptor.encrypt("same")).isNotEqualTo(encryptor.encrypt("same"));
    }

    @Test
    void nullsAndUnencryptedLegacyValuesPassThrough() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
        assertThat(encryptor.decrypt("written before encryption")).isEqualTo("written before encryption");
    }

    @Test
    void tamperedValuesAreRejected() {
        String stored = encryptor.encrypt("Notes from the discovery call");
        byte[] data = Base64.getDecoder().decode(stored.substring("enc:v1:".length()));
        data[data.length - 1] ^= 1;
        String tampered = "enc:v1:" + Base64.getEncoder().encodeToString(data);

        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyCannotReadTheData() {
        String stored = encryptor.encrypt("secret");

        assertThatThrownBy(() -> new FieldEncryptor(OTHER_KEY).decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENCRYPTION_KEY");
    }

    @Test
    void blindIndexIsStableCaseInsensitiveAndKeyed() {
        String index = encryptor.blindIndex("Priya@Acme.ai ");

        assertThat(index).isEqualTo(encryptor.blindIndex("priya@acme.ai"));
        assertThat(index).doesNotContain("priya");
        assertThat(index).isNotEqualTo(new FieldEncryptor(OTHER_KEY).blindIndex("priya@acme.ai"));
        assertThat(encryptor.blindIndex(" ")).isNull();
    }

    @Test
    void aMissingOrWeakKeyStopsTheAppWithClearInstructions() {
        assertThatThrownBy(() -> new FieldEncryptor(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 32");
        assertThatThrownBy(() -> new FieldEncryptor("not base64!"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldEncryptor(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
