package io.rocoto.security.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API tokens of the form {@code rct_<id>_<secret>}. The secret carries 256 bits of entropy, so a
 * single SHA-256 is an adequate one-way function; only {@code sha256$<b64>} is stored.
 */
public final class ApiTokens {

    public static final String PREFIX = "rct_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiTokens() {
    }

    public record Generated(String token, String storedHash) {
    }

    public record Parsed(String id, String secret) {
    }

    public static Generated generate(String id) {
        if (!id.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Token ids may only contain letters, digits and '-'");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String secretText = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new Generated(PREFIX + id + "_" + secretText, hash(secretText));
    }

    public static Parsed parse(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            return null;
        }
        int sep = token.indexOf('_', PREFIX.length());
        if (sep < 0 || sep == token.length() - 1) {
            return null;
        }
        return new Parsed(token.substring(PREFIX.length(), sep), token.substring(sep + 1));
    }

    public static String hash(String secret) {
        return "sha256$" + Base64.getEncoder().withoutPadding().encodeToString(sha256(secret));
    }

    public static boolean verify(String secret, String storedHash) {
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.UTF_8),
                String.valueOf(storedHash).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
