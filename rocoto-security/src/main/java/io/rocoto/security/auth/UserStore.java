package io.rocoto.security.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.config.ConfigLoader;
import io.rocoto.security.Glob;

/**
 * Rocoto's own credential store ({@code rocoto-users.yaml}): users with PBKDF2 password hashes,
 * hashed API tokens and client certificate mappings. The file is re-read when it changes, so
 * credentials can be rotated without a redeploy.
 */
public final class UserStore {

    private static final Logger LOG = Logger.getLogger(UserStore.class.getName());
    private static final long CHECK_INTERVAL_MILLIS = 2_000;

    public record User(String name, String passwordHash, Set<String> roles) {
    }

    public record Token(String id, String hash, Set<String> roles, Instant expires, List<ObjectName> mbeans) {
    }

    public record CertificateMapping(Glob subject, Glob san, String name, Set<String> roles) {
    }

    private record Snapshot(Map<String, User> users, Map<String, Token> tokens,
            List<CertificateMapping> certificates) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), List.of());
    }

    private final Path file;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile long loadedModified = Long.MIN_VALUE;
    private volatile long lastCheck;

    public UserStore(Path file) {
        this.file = file;
        refresh(true);
    }

    /** A store with fixed content, for tests and embedding. */
    public static UserStore of(Config content) {
        UserStore store = new UserStore(null);
        store.snapshot = parse(content);
        return store;
    }

    public Path file() {
        return file;
    }

    public boolean isEmpty() {
        Snapshot s = current();
        return s.users.isEmpty() && s.tokens.isEmpty() && s.certificates.isEmpty();
    }

    public User user(String name) {
        return current().users.get(name);
    }

    public Token token(String id) {
        return current().tokens.get(id);
    }

    public List<CertificateMapping> certificates() {
        return current().certificates;
    }

    private Snapshot current() {
        refresh(false);
        return snapshot;
    }

    private void refresh(boolean force) {
        if (file == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastCheck < CHECK_INTERVAL_MILLIS) {
            return;
        }
        lastCheck = now;
        try {
            if (!Files.isRegularFile(file)) {
                if (loadedModified != -1) {
                    LOG.warning("Rocoto user store " + file + " does not exist - built-in authentication is locked "
                            + "until a user or token is created with rocoto-tools");
                    loadedModified = -1;
                    snapshot = Snapshot.EMPTY;
                }
                return;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == loadedModified) {
                return;
            }
            warnIfWorldReadable();
            snapshot = parse(ConfigLoader.parse(file));
            loadedModified = modified;
            LOG.info("Loaded Rocoto user store " + file + " (" + snapshot.users.size() + " users, "
                    + snapshot.tokens.size() + " tokens, " + snapshot.certificates.size() + " certificate mappings)");
        } catch (IOException | RuntimeException e) {
            // Keep the last good state: a typo while editing must not open or lock the door.
            LOG.log(Level.SEVERE, "Cannot load Rocoto user store " + file + " - keeping previous content", e);
            try {
                loadedModified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException ignored) {
                loadedModified = -1;
            }
        }
    }

    private void warnIfWorldReadable() {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                LOG.warning("Rocoto user store " + file + " is accessible by other users - run: chmod 600 " + file);
            }
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX file system: nothing to check.
        }
    }

    private static Snapshot parse(Config c) {
        Map<String, User> users = new LinkedHashMap<>();
        for (Config u : c.subList("users")) {
            String name = required(u, "name", "users");
            String hash = required(u, "password", "users[" + name + "]");
            if (!PasswordHasher.isValidHash(hash)) {
                throw new ConfigException("users[" + name + "].password is not a valid PBKDF2 hash - "
                        + "create it with: rocoto-tools user add");
            }
            users.put(name, new User(name, hash, roles(u)));
        }
        Map<String, Token> tokens = new LinkedHashMap<>();
        for (Config t : c.subList("tokens")) {
            String id = required(t, "id", "tokens");
            String hash = required(t, "hash", "tokens[" + id + "]");
            String expires = t.string("expires", null);
            List<ObjectName> scope = new ArrayList<>();
            for (String pattern : t.strings("mbeans")) {
                try {
                    scope.add(new ObjectName(pattern));
                } catch (MalformedObjectNameException e) {
                    throw new ConfigException("tokens[" + id + "].mbeans: invalid ObjectName " + pattern);
                }
            }
            Instant exp;
            try {
                exp = expires == null ? null : Instant.parse(expires);
            } catch (RuntimeException e) {
                throw new ConfigException("tokens[" + id + "].expires must be an ISO-8601 instant");
            }
            tokens.put(id, new Token(id, hash, roles(t), exp, scope));
        }
        List<CertificateMapping> certs = new ArrayList<>();
        for (Config m : c.subList("certificates")) {
            String subject = m.string("subject", null);
            String san = m.string("san", null);
            if (subject == null && san == null) {
                throw new ConfigException("certificates: each mapping needs 'subject' or 'san'");
            }
            certs.add(new CertificateMapping(subject == null ? null : Glob.of(subject),
                    san == null ? null : Glob.of(san), m.string("name", null), roles(m)));
        }
        return new Snapshot(users, tokens, certs);
    }

    private static Set<String> roles(Config c) {
        return new LinkedHashSet<>(c.strings("roles"));
    }

    private static String required(Config c, String key, String where) {
        String v = c.string(key, null);
        if (v == null || v.isBlank()) {
            throw new ConfigException(where + ": '" + key + "' is required");
        }
        return v;
    }
}
