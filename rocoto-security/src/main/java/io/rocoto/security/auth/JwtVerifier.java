package io.rocoto.security.auth;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.json.JsonException;
import io.rocoto.core.json.JsonParser;

/**
 * Validates signed JWTs (RS256/384/512, ES256/384) using only JDK cryptography.
 *
 * <p>Deliberately strict: {@code alg: none} and HMAC algorithms are rejected, the key type must
 * match the algorithm, {@code iss}, {@code aud} and {@code exp} are mandatory.
 */
public final class JwtVerifier {

    private static final Logger LOG = Logger.getLogger(JwtVerifier.class.getName());
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private static final int MAX_TOKEN_LENGTH = 16 * 1024;

    public record Verified(String name, Set<String> roles) {
    }

    public static final class JwtException extends Exception {
        private static final long serialVersionUID = 1L;

        JwtException(String message) {
            super(message);
        }
    }

    private final String issuer;
    private final String audience;
    private final String nameClaim;
    private final String rolesClaim;
    private final Map<String, String> roleMapping;
    private final long clockSkewSeconds;
    private final Clock clock;
    private final Supplier<String> jwksSource;
    private final PublicKey staticKey;

    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile long lastRefresh;

    private JwtVerifier(Config jwt, Clock clock, Supplier<String> jwksSource, PublicKey staticKey) {
        this.issuer = require(jwt, "issuer");
        this.audience = require(jwt, "audience");
        this.nameClaim = jwt.string("nameClaim", "sub");
        this.rolesClaim = jwt.string("rolesClaim", "roles");
        this.clockSkewSeconds = jwt.longValue("clockSkewSeconds", 60);
        this.clock = clock;
        this.jwksSource = jwksSource;
        this.staticKey = staticKey;
        Map<String, String> mapping = new LinkedHashMap<>();
        jwt.sub("roleMapping").asMap().forEach((k, v) -> mapping.put(k, String.valueOf(v)));
        this.roleMapping = mapping;
    }

    /**
     * @param jwt        the {@code auth.builtin.jwt} section
     * @param sslContext TLS context for the JWKS download (revocation-checking, see OutboundTls)
     */
    public static JwtVerifier fromConfig(Config jwt, SSLContext sslContext, boolean allowInsecureJwks) {
        String pem = jwt.string("publicKey", null);
        String jwksUrl = jwt.string("jwksUrl", null);
        if ((pem == null) == (jwksUrl == null)) {
            throw new ConfigException("auth.builtin.jwt needs exactly one of 'jwksUrl' or 'publicKey'");
        }
        if (pem != null) {
            return new JwtVerifier(jwt, Clock.systemUTC(), null, parsePem(pem));
        }
        URI uri = URI.create(jwksUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !allowInsecureJwks) {
            throw new ConfigException("auth.builtin.jwt.jwksUrl must use https");
        }
        HttpClient client = HttpClient.newBuilder().sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        Supplier<String> source = () -> {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || response.body().length() > 1_000_000) {
                    throw new IllegalStateException("JWKS endpoint answered " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        };
        return new JwtVerifier(jwt, Clock.systemUTC(), source, null);
    }

    /** For tests: a verifier with an injectable JWKS document and clock. */
    static JwtVerifier forTest(Config jwt, Clock clock, Supplier<String> jwksSource, PublicKey staticKey) {
        return new JwtVerifier(jwt, clock, jwksSource, staticKey);
    }

    public Verified verify(String token) throws JwtException {
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new JwtException("token too long");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtException("not a signed JWT");
        }
        Map<String, Object> header = parseSegment(parts[0]);
        Map<String, Object> claims = parseSegment(parts[1]);
        String alg = String.valueOf(header.get("alg"));
        String jcaAlgorithm = switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            case "ES256" -> "SHA256withECDSAinP1363Format";
            case "ES384" -> "SHA384withECDSAinP1363Format";
            default -> throw new JwtException("unsupported algorithm " + alg);
        };
        PublicKey key = findKey(header.get("kid") instanceof String k ? k : null);
        boolean rsa = alg.startsWith("RS");
        if (rsa != (key instanceof RSAPublicKey) || (!rsa && !(key instanceof ECPublicKey))) {
            throw new JwtException("key type does not match algorithm");
        }
        try {
            Signature signature = Signature.getInstance(jcaAlgorithm);
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decode(parts[2]))) {
                throw new JwtException("invalid signature");
            }
        } catch (GeneralSecurityException e) {
            throw new JwtException("invalid signature");
        }
        validateClaims(claims);
        Object name = claimAt(claims, nameClaim);
        if (!(name instanceof String n) || n.isBlank()) {
            throw new JwtException("claim '" + nameClaim + "' missing");
        }
        return new Verified(n, roles(claims));
    }

    private void validateClaims(Map<String, Object> claims) throws JwtException {
        long now = clock.instant().getEpochSecond();
        if (!(claims.get("exp") instanceof Number exp)) {
            throw new JwtException("exp missing");
        }
        if (now > exp.longValue() + clockSkewSeconds) {
            throw new JwtException("token expired");
        }
        if (claims.get("nbf") instanceof Number nbf && now < nbf.longValue() - clockSkewSeconds) {
            throw new JwtException("token not yet valid");
        }
        if (!issuer.equals(claims.get("iss"))) {
            throw new JwtException("unexpected issuer");
        }
        Object aud = claims.get("aud");
        boolean audienceOk = aud instanceof Collection<?> c ? c.contains(audience) : audience.equals(aud);
        if (!audienceOk) {
            throw new JwtException("unexpected audience");
        }
    }

    private Set<String> roles(Map<String, Object> claims) {
        Set<String> roles = new LinkedHashSet<>();
        Object value = claimAt(claims, rolesClaim);
        Collection<?> raw = value instanceof Collection<?> c ? c
                : value instanceof String s ? List.of(s.trim().split("[\\s,]+")) : List.of();
        for (Object r : raw) {
            String role = String.valueOf(r);
            roles.add(roleMapping.getOrDefault(role, role));
        }
        return roles;
    }

    /** Looks up a claim; a dotted path such as {@code realm_access.roles} descends into objects. */
    private static Object claimAt(Map<String, Object> claims, String path) {
        if (claims.containsKey(path)) {
            return claims.get(path);
        }
        Object current = claims;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = m.get(part);
        }
        return current;
    }

    private PublicKey findKey(String kid) throws JwtException {
        if (staticKey != null) {
            return staticKey;
        }
        PublicKey key = lookup(kid);
        if (key == null && refresh()) {
            key = lookup(kid);
        }
        if (key == null) {
            throw new JwtException("no matching key in JWKS");
        }
        return key;
    }

    private PublicKey lookup(String kid) {
        Map<String, PublicKey> current = keys;
        if (kid != null) {
            return current.get(kid);
        }
        return current.size() == 1 ? current.values().iterator().next() : null;
    }

    /** Re-downloads the JWKS, but not more often than every 30 seconds (unknown kids are free to send). */
    private synchronized boolean refresh() {
        long now = clock.millis();
        if (lastRefresh != 0 && now - lastRefresh < MIN_REFRESH_INTERVAL.toMillis()) {
            return false;
        }
        lastRefresh = now;
        try {
            keys = parseJwks(jwksSource.get());
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot refresh JWKS: " + e.getMessage());
            return false;
        }
    }

    static Map<String, PublicKey> parseJwks(String json) {
        Map<String, PublicKey> result = new LinkedHashMap<>();
        Object keyList = JsonParser.parseObject(json).get("keys");
        if (!(keyList instanceof List<?> list)) {
            throw new IllegalStateException("JWKS has no 'keys'");
        }
        int anonymous = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> jwk) || "enc".equals(jwk.get("use"))) {
                continue;
            }
            try {
                PublicKey key = switch (String.valueOf(jwk.get("kty"))) {
                    case "RSA" -> KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                            unsigned(jwk.get("n")), unsigned(jwk.get("e"))));
                    case "EC" -> ecKey(jwk);
                    default -> null;
                };
                if (key != null) {
                    String kid = jwk.get("kid") instanceof String k ? k : "#" + anonymous++;
                    result.put(kid, key);
                }
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                LOG.fine("Skipping unusable JWK: " + e.getMessage());
            }
        }
        return result;
    }

    private static PublicKey ecKey(Map<?, ?> jwk) throws GeneralSecurityException {
        String curve = switch (String.valueOf(jwk.get("crv"))) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            default -> throw new IllegalArgumentException("unsupported curve");
        };
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(curve));
        ECPoint point = new ECPoint(unsigned(jwk.get("x")), unsigned(jwk.get("y")));
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(point, params.getParameterSpec(ECParameterSpec.class)));
    }

    private static BigInteger unsigned(Object base64Url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(String.valueOf(base64Url)));
    }

    private static PublicKey parsePem(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("auth.builtin.jwt.publicKey is not a PEM encoded public key");
        }
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                // try the next key type
            }
        }
        throw new ConfigException("auth.builtin.jwt.publicKey must be an RSA or EC public key (X.509/SPKI PEM)");
    }

    private static Map<String, Object> parseSegment(String segment) throws JwtException {
        try {
            return JsonParser.parseObject(new String(decode(segment), StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new JwtException("malformed JWT");
        }
    }

    private static byte[] decode(String base64Url) throws JwtException {
        try {
            return Base64.getUrlDecoder().decode(base64Url);
        } catch (IllegalArgumentException e) {
            throw new JwtException("malformed JWT");
        }
    }

    private static String require(Config jwt, String key) {
        String v = jwt.string(key, null);
        if (v == null || v.isBlank()) {
            throw new ConfigException("auth.builtin.jwt." + key + " is required");
        }
        return v;
    }
}
