package io.rocoto.security.auth;

import io.rocoto.security.RocotoIdentity;

/**
 * @param identity the caller, or {@code null} if authentication did not succeed
 * @param status   what happened
 * @param detail   reason for the audit log; never sent to the client
 * @param user     the user name that was presented, if any (for the audit log)
 */
public record AuthResult(RocotoIdentity identity, Status status, String detail, String user) {

    public enum Status {
        AUTHENTICATED,
        /** The request carried no credentials this authenticator understands. */
        NO_CREDENTIALS,
        FAILED,
        LOCKED,
        /** Credentials over plain HTTP are refused. */
        INSECURE_TRANSPORT
    }

    public static AuthResult success(RocotoIdentity identity) {
        return new AuthResult(identity, Status.AUTHENTICATED, null, identity.name());
    }

    public static AuthResult none() {
        return new AuthResult(null, Status.NO_CREDENTIALS, "no credentials", null);
    }

    public static AuthResult failed(String user, String detail) {
        return new AuthResult(null, Status.FAILED, detail, user);
    }

    public static AuthResult locked(String user) {
        return new AuthResult(null, Status.LOCKED, "too many failed attempts", user);
    }

    public static AuthResult insecure() {
        return new AuthResult(null, Status.INSECURE_TRANSPORT, "credentials sent without TLS", null);
    }

    public boolean authenticated() {
        return status == Status.AUTHENTICATED;
    }
}
