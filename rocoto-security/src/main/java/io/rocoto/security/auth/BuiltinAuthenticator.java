package io.rocoto.security.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import io.rocoto.core.config.Config;
import io.rocoto.security.RocotoIdentity;
import io.rocoto.security.tls.OutboundTls;
import io.rocoto.security.tls.RevocationChecker;
import io.rocoto.security.tls.RevocationMode;
import io.rocoto.security.tls.TrustMaterial;

/**
 * Rocoto's own authentication, independent of the application server: HTTP Basic, API tokens,
 * client certificates and JWT. Implemented with JDK classes only.
 */
public final class BuiltinAuthenticator {

    /** Verified against when the user does not exist, so both cases take the same time. */
    private static final String DUMMY_HASH = PasswordHasher.hash("rocoto-dummy".toCharArray());

    private static final long VERIFIED_TTL_MILLIS = 5 * 60 * 1000L;

    private record VerifiedPassword(byte[] fingerprint, long expires) {
    }

    private final java.util.Map<String, VerifiedPassword> verified = new java.util.concurrent.ConcurrentHashMap<>();
    private final byte[] processSalt = new byte[32];

    {
        new java.security.SecureRandom().nextBytes(processSalt);
    }

    private final UserStore store;
    private final Lockout lockout;
    private final boolean allowInsecureTransport;
    private final boolean basicEnabled;
    private final boolean tokensEnabled;
    private final boolean mtlsEnabled;
    private final RevocationChecker revocation;
    private final JwtVerifier jwt;
    private final Clock clock;
    private volatile boolean deferUnknownUsers;

    public BuiltinAuthenticator(UserStore store, Lockout lockout, boolean allowInsecureTransport,
            boolean basicEnabled, boolean tokensEnabled, boolean mtlsEnabled, RevocationChecker revocation,
            JwtVerifier jwt, Clock clock) {
        this.store = store;
        this.lockout = lockout;
        this.allowInsecureTransport = allowInsecureTransport;
        this.basicEnabled = basicEnabled;
        this.tokensEnabled = tokensEnabled;
        this.mtlsEnabled = mtlsEnabled;
        this.revocation = revocation;
        this.jwt = jwt;
        this.clock = clock;
    }

    /**
     * @param auth      the {@code auth} section of rocoto.yaml
     * @param tls       the {@code tls} section (outbound settings for the JWKS download)
     * @param configDir directory of rocoto.yaml; relative paths are resolved against it
     */
    public static BuiltinAuthenticator fromConfig(Config auth, Config tls, Path configDir) {
        Config b = auth.sub("builtin");
        Path usersFile = Path.of(b.string("usersFile", "rocoto-users.yaml"));
        if (!usersFile.isAbsolute() && configDir != null) {
            usersFile = configDir.resolve(usersFile);
        }
        Config lo = b.sub("lockout");
        Clock clock = Clock.systemUTC();
        Lockout lockout = new Lockout(Math.max(2, lo.integer("maxAttempts", 5)),
                Duration.ofSeconds(lo.longValue("windowSeconds", 300)),
                Duration.ofSeconds(lo.longValue("lockSeconds", 900)), clock);

        Config mtls = b.sub("mtls");
        RevocationChecker revocation = null;
        if (mtls.bool("enabled", false)) {
            String responder = mtls.string("ocspResponder", null);
            revocation = new RevocationChecker(TrustMaterial.anchors(mtls, "auth.builtin.mtls"),
                    RevocationMode.parse(mtls.string("revocation", "hard-fail"), "auth.builtin.mtls.revocation"),
                    responder == null ? null : URI.create(responder),
                    Duration.ofSeconds(mtls.longValue("cacheSeconds", 300)));
        }
        Config jwtConfig = b.sub("jwt");
        boolean insecure = auth.bool("allowInsecureTransport", false);
        JwtVerifier jwt = jwtConfig.bool("enabled", false)
                ? JwtVerifier.fromConfig(jwtConfig, OutboundTls.context(tls.sub("outbound")), insecure)
                : null;
        return new BuiltinAuthenticator(new UserStore(usersFile), lockout, insecure,
                b.bool("basic", true), b.bool("tokens", true), mtls.bool("enabled", false), revocation, jwt, clock);
    }

    /**
     * Chain mode: Basic credentials of a user Rocoto does not know are treated as "not for me" so
     * the application server's security domain gets to see them.
     */
    public void deferUnknownUsers(boolean defer) {
        this.deferUnknownUsers = defer;
    }

    public UserStore store() {
        return store;
    }

    public AuthResult authenticate(AuthRequest request) {
        String header = request.authorization();
        if (header != null) {
            int space = header.indexOf(' ');
            String scheme = space < 0 ? header : header.substring(0, space);
            String value = space < 0 ? "" : header.substring(space + 1).trim();
            if (scheme.equalsIgnoreCase("Basic") && basicEnabled) {
                return secure(request) ? basic(value, request) : AuthResult.insecure();
            }
            if (scheme.equalsIgnoreCase("Bearer")) {
                if (!secure(request)) {
                    return AuthResult.insecure();
                }
                if (value.startsWith(ApiTokens.PREFIX)) {
                    return tokensEnabled ? token(value, request) : AuthResult.none();
                }
                if (jwt != null) {
                    return jwt(value, request);
                }
            }
            return AuthResult.none();
        }
        if (mtlsEnabled && request.clientCertificates() != null && request.clientCertificates().length > 0) {
            return certificate(request.clientCertificates());
        }
        return AuthResult.none();
    }

    /** Challenges for the {@code WWW-Authenticate} header, depending on what is enabled. */
    public List<String> challenges() {
        List<String> challenges = new ArrayList<>();
        if (basicEnabled) {
            challenges.add("Basic realm=\"Rocoto\", charset=\"UTF-8\"");
        }
        if (tokensEnabled || jwt != null) {
            challenges.add("Bearer realm=\"Rocoto\"");
        }
        return challenges;
    }

    private boolean secure(AuthRequest request) {
        return request.secure() || allowInsecureTransport;
    }

    private AuthResult basic(String encoded, AuthRequest request) {
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return AuthResult.failed(null, "malformed Basic credentials");
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            return AuthResult.failed(null, "malformed Basic credentials");
        }
        String name = decoded.substring(0, colon);
        char[] password = decoded.substring(colon + 1).toCharArray();
        String userKey = "user:" + name;
        String addressKey = "addr:" + request.remoteAddress();
        if (lockout.isLocked(userKey) || lockout.isLocked(addressKey)) {
            return AuthResult.locked(name);
        }
        UserStore.User user = store.user(name);
        if (user == null && deferUnknownUsers) {
            return AuthResult.none();
        }
        boolean ok = verifyPassword(user, password);
        java.util.Arrays.fill(password, '\0');
        if (!ok) {
            lockout.recordFailure(userKey);
            lockout.recordFailure(addressKey);
            return AuthResult.failed(name, "wrong user name or password");
        }
        lockout.recordSuccess(userKey);
        return AuthResult.success(new RocotoIdentity(user.name(), user.roles(), "basic"));
    }

    /**
     * PBKDF2 is intentionally slow, which would make every polled request expensive. A successful
     * verification is therefore remembered for a few minutes as a salted SHA-256 fingerprint that
     * only exists in memory and is bound to the stored hash (changing the password invalidates it).
     */
    private boolean verifyPassword(UserStore.User user, char[] password) {
        if (user == null) {
            PasswordHasher.verify(password, DUMMY_HASH);
            return false;
        }
        byte[] fingerprint = fingerprint(user.passwordHash(), password);
        long now = clock.millis();
        VerifiedPassword cached = verified.get(user.name());
        if (cached != null && cached.expires > now
                && java.security.MessageDigest.isEqual(cached.fingerprint, fingerprint)) {
            return true;
        }
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            return false;
        }
        if (verified.size() > 1_000) {
            verified.clear();
        }
        verified.put(user.name(), new VerifiedPassword(fingerprint, now + VERIFIED_TTL_MILLIS));
        return true;
    }

    private byte[] fingerprint(String storedHash, char[] password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(processSalt);
            md.update(storedHash.getBytes(StandardCharsets.UTF_8));
            md.update(StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password)));
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private AuthResult token(String presented, AuthRequest request) {
        String addressKey = "addr:" + request.remoteAddress();
        if (lockout.isLocked(addressKey)) {
            return AuthResult.locked(null);
        }
        ApiTokens.Parsed parsed = ApiTokens.parse(presented);
        UserStore.Token token = parsed == null ? null : store.token(parsed.id());
        if (token == null || !ApiTokens.verify(parsed.secret(), token.hash())) {
            lockout.recordFailure(addressKey);
            return AuthResult.failed(parsed == null ? null : "token:" + parsed.id(), "unknown or wrong API token");
        }
        if (token.expires() != null && clock.instant().isAfter(token.expires())) {
            return AuthResult.failed("token:" + token.id(), "API token expired");
        }
        return AuthResult.success(new RocotoIdentity("token:" + token.id(), token.roles(), "token", token.mbeans()));
    }

    private AuthResult jwt(String presented, AuthRequest request) {
        String addressKey = "addr:" + request.remoteAddress();
        if (lockout.isLocked(addressKey)) {
            return AuthResult.locked(null);
        }
        try {
            JwtVerifier.Verified v = jwt.verify(presented);
            return AuthResult.success(new RocotoIdentity(v.name(), v.roles(), "jwt"));
        } catch (JwtVerifier.JwtException e) {
            lockout.recordFailure(addressKey);
            return AuthResult.failed(null, "JWT rejected: " + e.getMessage());
        }
    }

    private AuthResult certificate(X509Certificate[] chain) {
        X509Certificate cert = chain[0];
        String subject = cert.getSubjectX500Principal().getName();
        RevocationChecker.Result result = revocation.check(chain);
        if (!result.valid()) {
            return AuthResult.failed(subject, "client certificate rejected: " + result.detail());
        }
        List<String> sans = subjectAlternativeNames(cert);
        for (UserStore.CertificateMapping m : store.certificates()) {
            boolean subjectOk = m.subject() == null || m.subject().matches(subject);
            boolean sanOk = m.san() == null || sans.stream().anyMatch(m.san()::matches);
            if (subjectOk && sanOk) {
                String name = m.name() != null ? m.name() : commonName(subject);
                return AuthResult.success(new RocotoIdentity(name, m.roles(), "mtls"));
            }
        }
        return AuthResult.failed(subject, "no certificate mapping matches");
    }

    private static List<String> subjectAlternativeNames(X509Certificate cert) {
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    // [type, value]: 1 = rfc822Name, 2 = dNSName, 6 = URI
                    if (san.size() == 2 && san.get(1) instanceof String s) {
                        names.add(s.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // no usable SANs
        }
        return names;
    }

    private static String commonName(String dn) {
        try {
            for (Rdn rdn : new LdapName(dn).getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (InvalidNameException e) {
            // fall through
        }
        return dn;
    }
}
