package io.rocoto.security.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 from the JDK – no third-party crypto.
 * Format: {@code pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>}.
 */
public final class PasswordHasher {

    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final int MIN_ITERATIONS = 100_000;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(char[] password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    public static String hash(char[] password, int iterations) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, iterations, 32);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public static boolean verify(char[] password, String stored) {
        Parsed p = parse(stored);
        if (p == null) {
            return false;
        }
        byte[] actual = pbkdf2(password, p.salt, p.iterations, p.hash.length);
        return MessageDigest.isEqual(actual, p.hash);
    }

    /** @return {@code true} if the string is a well-formed hash with an acceptable work factor */
    public static boolean isValidHash(String stored) {
        return parse(stored) != null;
    }

    private record Parsed(int iterations, byte[] salt, byte[] hash) {
    }

    private static Parsed parse(String stored) {
        if (stored == null) {
            return null;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return null;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            if (iterations < MIN_ITERATIONS || salt.length < 8 || hash.length < 16) {
                return null;
            }
            return new Parsed(iterations, salt, hash);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Application servers register security providers of their own (WildFly: Elytron) that offer
     * key factories under the same algorithm name but with different semantics. Ask for the JDK's
     * implementation first and only fall back to provider search order on other JDK vendors.
     */
    private static SecretKeyFactory factory() throws NoSuchAlgorithmException {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "SunJCE");
        } catch (java.security.NoSuchProviderException | NoSuchAlgorithmException e) {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int length) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, length * 8);
        try {
            return factory().generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }
}
