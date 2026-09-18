package io.rocoto.security;

import java.util.List;
import java.util.Set;

import javax.management.ObjectName;

/**
 * The authenticated caller. Every authentication mechanism – container or built-in – ends in
 * this one type, so policy, masking and audit never care how somebody logged in.
 *
 * @param name       principal name
 * @param roles      roles granted to the caller
 * @param source     mechanism that authenticated the caller, e.g. {@code container}, {@code basic},
 *                   {@code token}, {@code mtls}, {@code jwt}
 * @param mbeanScope optional restriction (API tokens): if not empty, only MBeans matching one of
 *                   these patterns are accessible, whatever the roles say
 */
public record RocotoIdentity(String name, Set<String> roles, String source, List<ObjectName> mbeanScope) {

    public RocotoIdentity {
        roles = Set.copyOf(roles);
        mbeanScope = List.copyOf(mbeanScope);
    }

    public RocotoIdentity(String name, Set<String> roles, String source) {
        this(name, roles, source, List.of());
    }

    public boolean hasAnyRole(Set<String> wanted) {
        for (String role : wanted) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    public boolean inScope(ObjectName mbean) {
        if (mbeanScope.isEmpty()) {
            return true;
        }
        for (ObjectName pattern : mbeanScope) {
            if (pattern.apply(mbean)) {
                return true;
            }
        }
        return false;
    }
}
