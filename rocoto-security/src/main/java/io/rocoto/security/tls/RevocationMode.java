package io.rocoto.security.tls;

import java.util.Locale;

import io.rocoto.core.config.ConfigException;

/** How certificate revocation (OCSP, then CRL) is enforced. */
public enum RevocationMode {
    /** No revocation checking. */
    OFF,
    /** Revoked certificates are rejected; an unreachable responder is tolerated. */
    SOFT_FAIL,
    /** Revoked certificates and an undetermined revocation status are both rejected. */
    HARD_FAIL;

    public static RevocationMode parse(String value, String path) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + " must be one of off, soft-fail, hard-fail");
        }
    }
}
