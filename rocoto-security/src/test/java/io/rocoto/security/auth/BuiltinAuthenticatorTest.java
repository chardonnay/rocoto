package io.rocoto.security.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.rocoto.core.config.ConfigLoader;
import io.rocoto.security.auth.AuthResult.Status;

class BuiltinAuthenticatorTest {

    private static final String HASH = PasswordHasher.hash("correct horse".toCharArray(), 100_000);

    private final MutableClock clock = new MutableClock();
    private ApiTokens.Generated token;
    private ApiTokens.Generated expired;
    private BuiltinAuthenticator auth;

    @BeforeEach
    void setUp() {
        token = ApiTokens.generate("prometheus");
        expired = ApiTokens.generate("old");
        UserStore store = UserStore.of(ConfigLoader.parse("""
                users:
                  - name: ada
                    password: "%s"
                    roles: [rocoto-admin]
                tokens:
                  - id: prometheus
                    hash: "%s"
                    roles: [rocoto-reader]
                    mbeans: ["java.lang:*"]
                  - id: old
                    hash: "%s"
                    roles: [rocoto-reader]
                    expires: "2020-01-01T00:00:00Z"
                """.formatted(HASH, token.storedHash(), expired.storedHash())));
        auth = new BuiltinAuthenticator(store, new Lockout(3, Duration.ofMinutes(5), Duration.ofMinutes(15), clock),
                false, true, true, false, null, null, clock);
    }

    private static AuthRequest basic(String user, String password, boolean secure) {
        String header = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new AuthRequest(header, null, secure, "10.0.0.1");
    }

    private static AuthRequest bearer(String token) {
        return new AuthRequest("Bearer " + token, null, true, "10.0.0.2");
    }

    @Test
    void basicAuthentication() {
        AuthResult ok = auth.authenticate(basic("ada", "correct horse", true));
        assertTrue(ok.authenticated());
        assertEquals(Set.of("rocoto-admin"), ok.identity().roles());
        assertEquals("basic", ok.identity().source());
        // second call is served from the verified-password cache and must still reject wrong passwords
        assertTrue(auth.authenticate(basic("ada", "correct horse", true)).authenticated());
        assertEquals(Status.FAILED, auth.authenticate(basic("ada", "wrong", true)).status());
        assertEquals(Status.FAILED, auth.authenticate(basic("ghost", "correct horse", true)).status());
    }

    @Test
    void credentialsOverPlainHttpAreRefused() {
        assertEquals(Status.INSECURE_TRANSPORT, auth.authenticate(basic("ada", "correct horse", false)).status());
        assertEquals(Status.INSECURE_TRANSPORT,
                auth.authenticate(new AuthRequest("Bearer " + token.token(), null, false, "10.0.0.2")).status());
    }

    @Test
    void noCredentialsIsNotAFailure() {
        assertEquals(Status.NO_CREDENTIALS, auth.authenticate(new AuthRequest(null, null, true, "x")).status());
        assertEquals(Status.NO_CREDENTIALS,
                auth.authenticate(new AuthRequest("Negotiate abc", null, true, "x")).status());
    }

    @Test
    void lockoutAfterRepeatedFailuresAndRecoveryAfterTheLockPeriod() {
        for (int i = 0; i < 3; i++) {
            assertEquals(Status.FAILED, auth.authenticate(basic("ada", "guess" + i, true)).status());
        }
        // even the right password is refused while locked
        assertEquals(Status.LOCKED, auth.authenticate(basic("ada", "correct horse", true)).status());
        clock.advance(Duration.ofMinutes(16));
        assertTrue(auth.authenticate(basic("ada", "correct horse", true)).authenticated());
    }

    @Test
    void apiTokens() {
        AuthResult ok = auth.authenticate(bearer(token.token()));
        assertTrue(ok.authenticated());
        assertEquals("token:prometheus", ok.identity().name());
        assertEquals(1, ok.identity().mbeanScope().size());

        assertEquals(Status.FAILED, auth.authenticate(bearer(token.token() + "x")).status());
        assertEquals(Status.FAILED, auth.authenticate(bearer("rct_unknown_abc")).status());
        assertEquals(Status.FAILED, auth.authenticate(bearer(expired.token())).status());
        assertFalse(token.storedHash().contains(ApiTokens.parse(token.token()).secret()));
    }

    @Test
    void chainModeLeavesUnknownUsersToTheContainer() {
        auth.deferUnknownUsers(true);
        assertEquals(Status.NO_CREDENTIALS, auth.authenticate(basic("elytron-user", "pw", true)).status());
        assertEquals(Status.FAILED, auth.authenticate(basic("ada", "wrong", true)).status());
    }

    @Test
    void userStoreReloadsAndSurvivesBrokenEdits(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rocoto-users.yaml");
        UserStore store = new UserStore(file);
        assertTrue(store.isEmpty());

        Files.writeString(file, "users:\n  - name: bob\n    password: \"" + HASH + "\"\n    roles: [rocoto-reader]\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(5)));
        Thread.sleep(2100);
        assertEquals("bob", store.user("bob").name());

        Files.writeString(file, "users: [ this is : not valid");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(10)));
        Thread.sleep(2100);
        assertEquals("bob", store.user("bob").name(), "a broken edit keeps the last good state");
    }

    @Test
    void plainTextPasswordsInTheStoreAreRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(io.rocoto.core.config.ConfigException.class,
                () -> UserStore.of(ConfigLoader.parse("users:\n  - name: x\n    password: secret\n")));
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-18T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
