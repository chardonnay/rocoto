package io.rocoto.security.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigLoader;
import io.rocoto.security.auth.JwtVerifier.JwtException;

class JwtVerifierTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Config CONFIG = ConfigLoader.parse("""
            issuer: https://idp.example/realms/ops
            audience: rocoto
            nameClaim: preferred_username
            rolesClaim: realm_access.roles
            roleMapping: { ops-admin: rocoto-admin }
            """);

    private static KeyPair rsa;
    private static KeyPair ec;
    private static String jwks;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        rsa = rsaGen.generateKeyPair();
        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        ec = ecGen.generateKeyPair();

        RSAPublicKey r = (RSAPublicKey) rsa.getPublic();
        ECPublicKey e = (ECPublicKey) ec.getPublic();
        jwks = "{\"keys\":["
                + "{\"kty\":\"RSA\",\"kid\":\"r1\",\"n\":\"" + B64.encodeToString(unsigned(r.getModulus().toByteArray()))
                + "\",\"e\":\"" + B64.encodeToString(unsigned(r.getPublicExponent().toByteArray())) + "\"},"
                + "{\"kty\":\"EC\",\"kid\":\"e1\",\"crv\":\"P-256\",\"x\":\""
                + B64.encodeToString(fixed(e.getW().getAffineX().toByteArray(), 32)) + "\",\"y\":\""
                + B64.encodeToString(fixed(e.getW().getAffineY().toByteArray(), 32)) + "\"}]}";
    }

    private static byte[] unsigned(byte[] b) {
        return b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static byte[] fixed(byte[] b, int len) {
        byte[] u = unsigned(b);
        byte[] out = new byte[len];
        System.arraycopy(u, 0, out, len - u.length, u.length);
        return out;
    }

    private static String token(String alg, String kid, PrivateKey key, String jca, String claims) throws Exception {
        String header = "{\"alg\":\"" + alg + "\",\"kid\":\"" + kid + "\"}";
        String signingInput = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        String sig = "";
        if (key != null) {
            Signature s = Signature.getInstance(jca);
            s.initSign(key);
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            sig = B64.encodeToString(s.sign());
        }
        return signingInput + "." + sig;
    }

    private static String claims(long exp, String iss, String aud) {
        return "{\"iss\":\"" + iss + "\",\"aud\":[\"" + aud + "\",\"account\"],\"exp\":" + exp
                + ",\"preferred_username\":\"ada\",\"realm_access\":{\"roles\":[\"ops-admin\",\"rocoto-reader\"]}}";
    }

    private static String validClaims() {
        return claims(NOW.getEpochSecond() + 300, "https://idp.example/realms/ops", "rocoto");
    }

    private final JwtVerifier verifier = JwtVerifier.forTest(CONFIG, CLOCK, () -> jwks, null);

    @Test
    void acceptsRsaAndEcTokensAndMapsRoles() throws Exception {
        JwtVerifier.Verified v = verifier.verify(token("RS256", "r1", rsa.getPrivate(), "SHA256withRSA", validClaims()));
        assertEquals("ada", v.name());
        assertEquals(Set.of("rocoto-admin", "rocoto-reader"), v.roles());
        verifier.verify(token("ES256", "e1", ec.getPrivate(), "SHA256withECDSAinP1363Format", validClaims()));
    }

    @Test
    void rejectsUnsignedAndHmacTokens() throws Exception {
        assertThrows(JwtException.class, () -> verifier.verify(token("none", "r1", null, null, validClaims())));
        assertThrows(JwtException.class, () -> verifier.verify(token("HS256", "r1", null, null, validClaims())));
    }

    @Test
    void rejectsAlgorithmKeyMismatch() throws Exception {
        // RS256 header pointing at the EC key
        assertThrows(JwtException.class,
                () -> verifier.verify(token("RS256", "e1", rsa.getPrivate(), "SHA256withRSA", validClaims())));
    }

    @Test
    void rejectsTamperedExpiredAndForeignTokens() throws Exception {
        String good = token("RS256", "r1", rsa.getPrivate(), "SHA256withRSA", validClaims());
        String[] parts = good.split("\\.");
        String forgedClaims = B64.encodeToString(validClaims().replace("ada", "eve").getBytes(StandardCharsets.UTF_8));
        assertThrows(JwtException.class, () -> verifier.verify(parts[0] + "." + forgedClaims + "." + parts[2]));

        assertThrows(JwtException.class, () -> verifier.verify(token("RS256", "r1", rsa.getPrivate(),
                "SHA256withRSA", claims(NOW.getEpochSecond() - 3600, "https://idp.example/realms/ops", "rocoto"))));
        assertThrows(JwtException.class, () -> verifier.verify(token("RS256", "r1", rsa.getPrivate(),
                "SHA256withRSA", claims(NOW.getEpochSecond() + 300, "https://evil.example", "rocoto"))));
        assertThrows(JwtException.class, () -> verifier.verify(token("RS256", "r1", rsa.getPrivate(),
                "SHA256withRSA", claims(NOW.getEpochSecond() + 300, "https://idp.example/realms/ops", "other"))));
        assertThrows(JwtException.class, () -> verifier.verify(token("RS256", "unknown", rsa.getPrivate(),
                "SHA256withRSA", validClaims())));
        assertThrows(JwtException.class, () -> verifier.verify("not.a.jwt"));
    }
}
