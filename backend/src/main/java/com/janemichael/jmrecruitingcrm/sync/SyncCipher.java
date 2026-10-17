package com.janemichael.jmrecruitingcrm.sync;

import javax.crypto.AEADBadTagException;
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
 * Encrypts what computers send each other: the files they leave in the shared folder, and what
 * they exchange over a direct connection (AES-256-GCM), and signs direct requests (HMAC-SHA256).
 *
 * <p>The keys come from the sync token, which every computer in the group knows even while its
 * CRM is locked, so syncing never needs the CRM key itself, and the token itself is never sent.
 * Each sealed item is bound to a name (its place in the folder, or the request it answers), so it
 * can't be swapped for another without being noticed. The fields that identify people are already
 * encrypted with the CRM key inside; this layer covers everything else.
 */
final class SyncCipher {

    private static final byte[] MAGIC = "JMF1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Something that couldn't be opened: another CRM's, damaged, or not fully copied yet. */
    static class UnreadableException extends Exception {
        UnreadableException(String message) {
            super(message);
        }
    }

    private final SecretKey sealKey;
    private final SecretKey signKey;

    private SyncCipher(byte[] sealKey, byte[] signKey) {
        this.sealKey = new SecretKeySpec(sealKey, "AES");
        this.signKey = new SecretKeySpec(signKey, "HmacSHA256");
    }

    /** For the files in the shared folder. */
    static SyncCipher forFolder(String syncToken) {
        return new SyncCipher(derive(syncToken, "jmcrm sync folder v1"), derive(syncToken, "jmcrm sync folder sign v1"));
    }

    /** For backups, kept on each computer. */
    static SyncCipher forBackups(String syncToken) {
        return new SyncCipher(derive(syncToken, "jmcrm backup v1"), derive(syncToken, "jmcrm backup sign v1"));
    }

    /** For direct connections between computers. */
    static SyncCipher forDirect(String syncToken) {
        return new SyncCipher(derive(syncToken, "jmcrm direct sync v1"), derive(syncToken, "jmcrm direct sign v1"));
    }

    byte[] seal(String name, byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sealKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(MAGIC.length + IV_BYTES + sealed.length)
                    .put(MAGIC).put(iv).put(sealed).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt sync data", exception);
        }
    }

    byte[] open(String name, byte[] stored) throws UnreadableException {
        if (stored.length < MAGIC.length + IV_BYTES + TAG_BITS / 8
                || !Arrays.equals(Arrays.copyOf(stored, MAGIC.length), MAGIC)) {
            throw new UnreadableException(name + " isn't complete JM CRM sync data");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sealKey, new GCMParameterSpec(TAG_BITS, stored, MAGIC.length, IV_BYTES));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            int start = MAGIC.length + IV_BYTES;
            return cipher.doFinal(stored, start, stored.length - start);
        } catch (AEADBadTagException wrongKeyOrDamaged) {
            throw new UnreadableException(name + " was written with a different CRM key, or is damaged");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not decrypt sync data", exception);
        }
    }

    /** A signature over a message, proving the sender knows the sync token without sending it. */
    String sign(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signKey);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not sign a sync request", exception);
        }
    }

    boolean verify(String message, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(message).getBytes(StandardCharsets.US_ASCII),
                signature.trim().getBytes(StandardCharsets.US_ASCII));
    }

    /** SHA-256 of a request body, for signing. */
    static String digest(byte[] body) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static byte[] derive(String syncToken, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(syncToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not prepare the sync keys", exception);
        }
    }
}
