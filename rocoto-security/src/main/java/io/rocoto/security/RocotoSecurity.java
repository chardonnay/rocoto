package io.rocoto.security;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.jmx.MBeanAccess;
import io.rocoto.security.audit.AuditLogger;
import io.rocoto.security.auth.BuiltinAuthenticator;
import io.rocoto.security.policy.AccessPolicy;
import io.rocoto.security.policy.ValueMasker;

/** Everything security-related, built once from rocoto.yaml at start-up. */
public final class RocotoSecurity implements AutoCloseable {

    /** Servlet context attribute under which the instance is published. */
    public static final String CONTEXT_ATTRIBUTE = "io.rocoto.security";
    /** Request attribute holding the authenticated {@link RocotoIdentity}. */
    public static final String IDENTITY_ATTRIBUTE = "io.rocoto.identity";

    private static final Logger LOG = Logger.getLogger(RocotoSecurity.class.getName());

    public enum AuthMode { CONTAINER, BUILTIN, CHAIN }

    private final AuthMode mode;
    private final BuiltinAuthenticator builtin;
    private final AccessPolicy policy;
    private final ValueMasker masker;
    private final AuditLogger audit;
    private final List<String> allowedOrigins;
    private final boolean anonymousHealth;
    private final String containerChallenge;

    private RocotoSecurity(AuthMode mode, BuiltinAuthenticator builtin, AccessPolicy policy, ValueMasker masker,
            AuditLogger audit, List<String> allowedOrigins, boolean anonymousHealth, String containerChallenge) {
        this.containerChallenge = containerChallenge;
        this.mode = mode;
        this.builtin = builtin;
        this.policy = policy;
        this.masker = masker;
        this.audit = audit;
        this.allowedOrigins = allowedOrigins;
        this.anonymousHealth = anonymousHealth;
    }

    public static RocotoSecurity fromConfig(Config config, Path configDir) {
        Config auth = config.sub("auth");
        String modeName = auth.string("mode", "builtin");
        AuthMode mode;
        try {
            mode = AuthMode.valueOf(modeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("auth.mode must be one of container, builtin, chain");
        }
        BuiltinAuthenticator builtin = null;
        if (mode != AuthMode.CONTAINER) {
            builtin = BuiltinAuthenticator.fromConfig(auth, config.sub("tls"), configDir);
            builtin.deferUnknownUsers(mode == AuthMode.CHAIN);
            if (auth.bool("allowInsecureTransport", false)) {
                LOG.warning("auth.allowInsecureTransport is enabled: credentials are accepted over plain HTTP. "
                        + "Do not use this outside of local testing.");
            }
        }
        Config policySection = config.sub("policy");
        List<String> origins = config.strings("cors.allowedOrigins");
        if (origins.contains("*")) {
            throw new ConfigException("cors.allowedOrigins must list explicit origins; '*' is not supported");
        }
        LOG.info("Rocoto security initialised: auth.mode=" + modeName.toLowerCase(Locale.ROOT));
        return new RocotoSecurity(mode, builtin, AccessPolicy.fromConfig(policySection),
                ValueMasker.fromConfig(policySection), AuditLogger.fromConfig(config.sub("audit")), origins,
                config.bool("health.allowAnonymous", false),
                auth.string("container.challenge", "Basic realm=\"Rocoto\", charset=\"UTF-8\""));
    }

    /**
     * Whether credentials that were valid when a long-running stream started are still valid:
     * the built-in user or API token still exists, has not expired and carries the same roles.
     * Identities established by other mechanisms (container, JWT, mTLS) cannot be re-validated
     * without the original request; their streams are bounded by {@code stream.maxDurationSeconds}.
     */
    public boolean stillValid(RocotoIdentity identity) {
        if (builtin == null) {
            return true;
        }
        switch (identity.source()) {
            case "basic": {
                var user = builtin.store().user(identity.name());
                return user != null && user.roles().equals(identity.roles());
            }
            case "token": {
                var token = builtin.store().token(identity.name().substring("token:".length()));
                return token != null && token.roles().equals(identity.roles())
                        && (token.expires() == null || java.time.Instant.now().isBefore(token.expires()));
            }
            default:
                return true;
        }
    }

    public RequestPipeline pipeline(MBeanAccess access) {
        return new RequestPipeline(access, policy, masker, audit);
    }

    public AuthMode mode() {
        return mode;
    }

    public BuiltinAuthenticator builtin() {
        return builtin;
    }

    public AccessPolicy policy() {
        return policy;
    }

    public ValueMasker masker() {
        return masker;
    }

    public AuditLogger audit() {
        return audit;
    }

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    /**
     * {@code WWW-Authenticate} value Rocoto sends in container/chain mode when the application
     * server did not issue a challenge itself ({@code auth.container.challenge}).
     */
    public String containerChallenge() {
        return containerChallenge;
    }

    public boolean anonymousHealth() {
        return anonymousHealth;
    }

    @Override
    public void close() {
        audit.close();
    }
}
