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
    void filesAreEncryptedAndTiedToTheirKey() {
        byte[] resume = "%PDF-1.7 Dana Reyes, Staff ML Engineer".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] stored = encryptor.encryptBytes(resume, "candidate file a1");

        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("JMB1").doesNotContain("Dana");
        assertThat(encryptor.decryptBytes(stored, "candidate file a1")).isEqualTo(resume);
        // Stored under another file's key, it can't be opened.
        assertThatThrownBy(() -> encryptor.decryptBytes(stored, "candidate file b2")).isInstanceOf(IllegalStateException.class);
        byte[] tampered = stored.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> encryptor.decryptBytes(tampered, "candidate file a1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldEncryptor(OTHER_KEY).decryptBytes(stored, "candidate file a1"))
                .isInstanceOf(IllegalStateException.class);
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

    @Test
    void aLockedCopyKnowsItsSyncTokenButCannotReadOrWriteUntilUnlocked() {
        FieldEncryptor locked = new FieldEncryptor("", true, FieldEncryptor.syncTokenFor(TEST_KEY));
        String stored = encryptor.encrypt("Dana Reyes");

        assertThat(locked.isConfigured()).isFalse();
        assertThat(locked.hasSyncToken()).isTrue();
        assertThat(locked.syncToken()).isEqualTo(encryptor.syncToken());
        assertThatThrownBy(() -> locked.decrypt(stored)).isInstanceOf(FieldEncryptor.CrmLockedException.class);
        assertThatThrownBy(() -> locked.encrypt("x")).isInstanceOf(FieldEncryptor.CrmLockedException.class);
        assertThatThrownBy(() -> locked.blindIndex("x")).isInstanceOf(FieldEncryptor.CrmLockedException.class);

        assertThat(locked.isThisCrmsKey(OTHER_KEY)).isFalse();
        assertThat(locked.isThisCrmsKey("not a key")).isFalse();
        assertThat(locked.isThisCrmsKey(TEST_KEY)).isTrue();
        assertThatThrownBy(() -> locked.configure(OTHER_KEY)).hasMessage("That isn't this CRM's key.");
        assertThat(locked.isConfigured()).isFalse();

        locked.configure(TEST_KEY);
        assertThat(locked.decrypt(stored)).isEqualTo("Dana Reyes");
        assertThat(locked.exportKey()).isEqualTo(TEST_KEY);

        locked.lock();
        assertThat(locked.isConfigured()).isFalse();
        assertThat(locked.hasSyncToken()).isTrue();
        assertThatThrownBy(() -> locked.decrypt(stored)).isInstanceOf(FieldEncryptor.CrmLockedException.class);
    }

    @Test
    void aDesktopCopyThatWasNeverSetUpHasNoSyncToken() {
        FieldEncryptor fresh = new FieldEncryptor(null, true, null);

        assertThat(fresh.isConfigured()).isFalse();
        assertThat(fresh.hasSyncToken()).isFalse();
        assertThat(fresh.isThisCrmsKey(TEST_KEY)).isFalse();
    }
}
