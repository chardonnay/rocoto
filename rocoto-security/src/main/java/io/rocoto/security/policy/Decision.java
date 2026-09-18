package io.rocoto.security.policy;

/** Outcome of a policy check, with a reason suitable for the audit log (not for the client). */
public record Decision(boolean allowed, String reason) {

    public static Decision allow(String reason) {
        return new Decision(true, reason);
    }

    public static Decision deny(String reason) {
        return new Decision(false, reason);
    }
}
