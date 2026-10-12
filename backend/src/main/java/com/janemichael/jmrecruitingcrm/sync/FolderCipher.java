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
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Encrypts the files computers leave for each other in the shared folder (AES-256-GCM).
 *
 * <p>The key comes from the sync token, which every computer in the group knows even while its CRM
 * is locked, so syncing never needs the CRM key itself. Each file is bound to its name in the
 * folder, so files can't be swapped or renamed without being noticed. The fields that identify
 * people are already encrypted with the CRM key inside; this layer covers everything else.
 */
final class FolderCipher {

    private static final byte[] MAGIC = "JMF1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A file that couldn't be opened: another CRM's, damaged, or not fully copied yet. */
    static class UnreadableException extends Exception {
        UnreadableException(String message) {
            super(message);
        }
    }

    private final SecretKey key;

    private FolderCipher(SecretKey key) {
        this.key = key;
    }

    static FolderCipher forToken(String syncToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(syncToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] derived = mac.doFinal("jmcrm sync folder v1".getBytes(StandardCharsets.UTF_8));
            return new FolderCipher(new SecretKeySpec(derived, "AES"));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not prepare the sync folder key", exception);
        }
    }

    byte[] seal(String name, byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(MAGIC.length + IV_BYTES + sealed.length)
                    .put(MAGIC).put(iv).put(sealed).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt a sync file", exception);
        }
    }

    byte[] open(String name, byte[] stored) throws UnreadableException {
        if (stored.length < MAGIC.length + IV_BYTES + TAG_BITS / 8
                || !Arrays.equals(Arrays.copyOf(stored, MAGIC.length), MAGIC)) {
            throw new UnreadableException(name + " isn't a complete JM CRM sync file");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, MAGIC.length, IV_BYTES));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            int start = MAGIC.length + IV_BYTES;
            return cipher.doFinal(stored, start, stored.length - start);
        } catch (AEADBadTagException wrongKeyOrDamaged) {
            throw new UnreadableException(name + " was written with a different CRM key, or is damaged");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not decrypt a sync file", exception);
        }
    }
}
