package io.rocoto.security;

/**
 * Who is calling, from where and through which door.
 *
 * @param channel {@code api}, {@code jolokia}, {@code metrics} or {@code stream}
 */
public record CallContext(RocotoIdentity identity, String remoteAddress, String channel) {
}
