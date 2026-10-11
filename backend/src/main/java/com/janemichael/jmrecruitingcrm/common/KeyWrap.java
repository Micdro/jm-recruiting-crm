package com.janemichael.jmrecruitingcrm.common;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Locks the CRM key with a person's password, so the key never has to sit on disk in the clear.
 *
 * <p>The password is stretched with Argon2id (64 MiB, 3 passes) into a key that encrypts the CRM
 * key with AES-256-GCM. Each person has their own copy, stored with their account and shared
 * between computers; signing in with the right password opens it. Guessing a password offline
 * costs an attacker 64 MiB and a fraction of a second per guess.
 */
public final class KeyWrap {

    static final String PREFIX = "kw1:";
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MEMORY_KIB = 64 * 1024;
    private static final int ITERATIONS = 3;
    private static final byte[] CONTEXT = "jmcrm key wrap v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The password didn't open the stored key. */
    public static class WrongPasswordException extends Exception {
        public WrongPasswordException() {
            super("That password doesn't open the CRM key");
        }
    }

    private KeyWrap() {
    }

    public static String wrap(String base64Key, String password) {
        byte[] key = FieldEncryptor.decodeKey(base64Key);
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        byte[] kek = stretch(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(CONTEXT);
            byte[] sealed = cipher.doFinal(key);
            byte[] stored = ByteBuffer.allocate(SALT_BYTES + IV_BYTES + sealed.length)
                    .put(salt).put(iv).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not lock the CRM key", exception);
        } finally {
            Arrays.fill(kek, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Returns the CRM key, base64 encoded. */
    public static String unwrap(String wrapped, String password) throws WrongPasswordException {
        if (wrapped == null || !wrapped.startsWith(PREFIX) || password == null) {
            throw new WrongPasswordException();
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(wrapped.substring(PREFIX.length()));
        } catch (IllegalArgumentException unreadable) {
            throw new WrongPasswordException();
        }
        if (data.length <= SALT_BYTES + IV_BYTES) {
            throw new WrongPasswordException();
        }
        byte[] salt = Arrays.copyOfRange(data, 0, SALT_BYTES);
        byte[] kek = stretch(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(TAG_BITS, data, SALT_BYTES, IV_BYTES));
            cipher.updateAAD(CONTEXT);
            byte[] key = cipher.doFinal(data, SALT_BYTES + IV_BYTES, data.length - SALT_BYTES - IV_BYTES);
            String encoded = Base64.getEncoder().encodeToString(key);
            Arrays.fill(key, (byte) 0);
            return encoded;
        } catch (AEADBadTagException wrongPassword) {
            throw new WrongPasswordException();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not open the CRM key", exception);
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    private static byte[] stretch(String password, byte[] salt) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withParallelism(1)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] out = new byte[32];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
