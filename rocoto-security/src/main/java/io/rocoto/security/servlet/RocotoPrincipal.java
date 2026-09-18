package io.rocoto.security.servlet;

import java.security.Principal;

import io.rocoto.security.RocotoIdentity;

/** {@link Principal} view of a {@link RocotoIdentity}. */
public record RocotoPrincipal(RocotoIdentity identity) implements Principal {

    @Override
    public String getName() {
        return identity.name();
    }
}
