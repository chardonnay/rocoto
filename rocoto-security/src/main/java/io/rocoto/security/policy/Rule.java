package io.rocoto.security.policy;

import java.util.List;
import java.util.Set;

import javax.management.ObjectName;

import io.rocoto.core.request.Operation;
import io.rocoto.core.request.Target;
import io.rocoto.security.Glob;
import io.rocoto.security.RocotoIdentity;

/**
 * One policy rule.
 *
 * @param roles      roles the rule applies to; empty means every authenticated caller
 * @param operations operations the rule covers
 * @param mbean      ObjectName or pattern
 * @param members    globs for attribute (READ/WRITE) or operation (EXEC) names; empty means all
 */
public record Rule(Effect effect, Set<String> roles, Set<Operation> operations, ObjectName mbean,
        List<Glob> members) {

    public enum Effect { ALLOW, DENY }

    public boolean matches(RocotoIdentity identity, Target target) {
        if (!operations.contains(target.operation())) {
            return false;
        }
        if (!roles.isEmpty() && !identity.hasAnyRole(roles)) {
            return false;
        }
        if (!mbean.apply(target.mbean())) {
            return false;
        }
        if (members.isEmpty()) {
            return true;
        }
        if (target.member() == null) {
            // LIST/SEARCH address the whole MBean. A rule limited to some members lets the MBean be
            // listed (allow) but must not hide it completely (deny).
            return effect == Effect.ALLOW;
        }
        for (Glob g : members) {
            if (g.matches(target.member())) {
                return true;
            }
        }
        return false;
    }
}
