package com.janemichael.jmrecruitingcrm.common;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts everything that identifies a person or company (names, titles, contact details, notes,
 * money) before it reaches the database, so a stolen database or backup is unreadable without the key.
 *
 * <ul>
 *   <li>AES-256-GCM with a random 96-bit IV per value. GCM also detects tampering.</li>
 *   <li>Stored as {@code enc:v1:<base64(iv | ciphertext | tag)>}. The version allows key rotation later.</li>
 *   <li>Emails also get a keyed HMAC-SHA256 "blind index" so duplicates can be found without decrypting.</li>
 *   <li>Both keys are derived from APP_ENCRYPTION_KEY (32 random bytes, base64). Lose it and the data is lost.</li>
 * </ul>
 */
@Component
public class FieldEncryptor implements InitializingBean {

    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static volatile FieldEncryptor current;

    private final SecureRandom random = new SecureRandom();
    /** Null while locked: on the desktop, until someone signs in. */
    private volatile Keys keys;
    /** Known even while locked, so this computer can keep syncing scrambled data. */
    private volatile String syncToken;

    /** Keys derived from the master key, swapped in together. */
    private record Keys(byte[] master, SecretKey encryption, SecretKey index) {
    }

    public FieldEncryptor(String base64Key) {
        this(base64Key, false, null);
    }

    /**
     * A server starts with its key and stays unlocked. On the desktop the key isn't stored on disk:
     * the app starts locked, knowing only the sync token, and is unlocked when someone signs in
     * (see {@link #configure(String)} and {@link #lock()}).
     */
    @Autowired
    public FieldEncryptor(
            @Value("${app.encryption.key:}") String base64Key,
            @Value("${app.desktop.enabled:false}") boolean desktop,
            @Value("${app.sync.token:}") String syncToken
    ) {
        if (syncToken != null && !syncToken.isBlank()) {
            this.syncToken = syncToken.trim();
        }
        if (desktop && (base64Key == null || base64Key.isBlank())) {
            return;
        }
        configure(base64Key);
    }

    /**
     * Unlocks with the master key. If this copy already knows its sync token, the key must be the one
     * that token came from, so a wrong key can never be used to write data.
     */
    public void configure(String base64Key) {
        byte[] master = decodeKey(base64Key);
        String token = token(master);
        String known = syncToken;
        if (known != null && !MessageDigest.isEqual(known.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("That isn't this CRM's key.");
        }
        this.keys = new Keys(master,
                new SecretKeySpec(derive(master, "jmcrm field encryption v1"), "AES"),
                new SecretKeySpec(derive(master, "jmcrm blind index v1"), "HmacSHA256"));
        this.syncToken = token;
    }

    /** Forgets the key. Encrypted fields can't be read or written until {@link #configure(String)} again. */
    public void lock() {
        Keys current = keys;
        keys = null;
        if (current != null) {
            Arrays.fill(current.master(), (byte) 0);
        }
    }

    /** True when the key is loaded: always on a server, and on the desktop while someone is signed in. */
    public boolean isConfigured() {
        return keys != null;
    }

    public boolean hasSyncToken() {
        return syncToken != null;
    }

    /** Whether a key belongs to this CRM, checked without unlocking. */
    public boolean isThisCrmsKey(String base64Key) {
        String known = syncToken;
        if (known == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(known.getBytes(StandardCharsets.UTF_8),
                    syncTokenFor(base64Key).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    /**
     * The shared secret computers present to each other when syncing. Derived from the master key,
     * so every computer with the same key accepts the others and nobody else.
     */
    public String syncToken() {
        String token = syncToken;
        if (token == null) {
            throw new IllegalStateException("Encryption is not set up yet. Finish setup first.");
        }
        return token;
    }

    /** The sync token a key produces, for setup and for checking a key someone typed in. */
    public static String syncTokenFor(String base64Key) {
        return token(decodeKey(base64Key));
    }

    /** The master key, base64 encoded, for locking it with a person's password. Unlocked only. */
    public String exportKey() {
        return Base64.getEncoder().encodeToString(keys().master());
    }

    /** Generates a new random master key, base64 encoded. */
    public static String newKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String token(byte[] master) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(derive(master, "jmcrm sync token v1"));
    }

    private Keys keys() {
        Keys current = keys;
        if (current == null) {
            throw new CrmLockedException();
        }
        return current;
    }

    /** Thrown when encrypted data is needed while the CRM is locked. */
    public static class CrmLockedException extends IllegalStateException {
        public CrmLockedException() {
            super("The CRM is locked. Sign in to unlock it.");
        }
    }

    /** Called by Spring once the bean is ready. Instances made with {@code new} (in tests) stay local. */
    @Override
    public void afterPropertiesSet() {
        current = this;
    }

    /** The configured encryptor, for JPA converters (which Hibernate creates outside Spring's wiring). */
    public static FieldEncryptor current() {
        FieldEncryptor encryptor = current;
        if (encryptor == null) {
            throw new IllegalStateException("Field encryption is not configured");
        }
        return encryptor;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys().encryption(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] stored = ByteBuffer.allocate(IV_BYTES + ciphertext.length).put(iv).put(ciphertext).array();
            return PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt a field", exception);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith("enc:")) {
            // Written before encryption was switched on. EncryptionBackfill encrypts these at startup.
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unsupported encryption version");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys().encryption(), new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Could not decrypt a field. Check that APP_ENCRYPTION_KEY is the key the data was saved with.",
                    exception);
        }
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** A keyed, case-insensitive fingerprint of a value, for finding duplicates without decrypting. */
    public String blindIndex(String value) {
        String normalized = Text.key(value);
        if (normalized == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keys().index());
            byte[] digest = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not index a field", exception);
        }
    }

    private static byte[] derive(byte[] master, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not derive encryption keys", exception);
        }
    }

    static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "APP_ENCRYPTION_KEY is not set. Generate one with `openssl rand -base64 32` and keep a copy "
                            + "in your password manager: without it, encrypted data can't be read.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must be base64 (from `openssl rand -base64 32`)");
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must be 32 bytes (256 bits) of base64");
        }
        return key;
    }
}
