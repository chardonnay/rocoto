package io.rocoto.security.policy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.request.Operation;
import io.rocoto.core.request.Target;
import io.rocoto.security.Glob;
import io.rocoto.security.RocotoIdentity;

/**
 * Decides whether an identity may perform an operation on a target.
 *
 * <p>Evaluation order – the first match wins:
 * <ol>
 *   <li>token scope: targets outside an API token's MBean scope are denied</li>
 *   <li>built-in {@link Denylist} (WRITE/EXEC only, not overridable)</li>
 *   <li>{@code deny} rules</li>
 *   <li>{@code allow} rules</li>
 *   <li>default: READ/LIST/SEARCH allowed for the {@code readRoles}; WRITE/EXEC denied</li>
 * </ol>
 */
public final class AccessPolicy {

    public static final String ROLE_READER = "rocoto-reader";
    public static final String ROLE_OPERATOR = "rocoto-operator";
    public static final String ROLE_ADMIN = "rocoto-admin";

    private final Set<String> readRoles;
    private final List<Rule> denyRules;
    private final List<Rule> allowRules;

    private AccessPolicy(Set<String> readRoles, List<Rule> denyRules, List<Rule> allowRules) {
        this.readRoles = Set.copyOf(readRoles);
        this.denyRules = List.copyOf(denyRules);
        this.allowRules = List.copyOf(allowRules);
    }

    /** The policy that applies when nothing is configured: read-only for the three standard roles. */
    public static AccessPolicy secureDefault() {
        return fromConfig(Config.empty());
    }

    /** @param policy the {@code policy} section of rocoto.yaml */
    public static AccessPolicy fromConfig(Config policy) {
        Set<String> readRoles = new LinkedHashSet<>(policy.strings("readRoles"));
        if (!policy.has("readRoles")) {
            readRoles.addAll(List.of(ROLE_READER, ROLE_OPERATOR, ROLE_ADMIN));
        }
        List<Rule> deny = new ArrayList<>();
        List<Rule> allow = new ArrayList<>();
        int index = 0;
        for (Config rc : policy.subList("rules")) {
            String where = "policy.rules[" + index++ + "]";
            boolean isAllow = rc.has("allow");
            if (isAllow == rc.has("deny")) {
                throw new ConfigException(where + " needs exactly one of 'allow' or 'deny'");
            }
            Set<Operation> ops = parseOperations(rc.strings(isAllow ? "allow" : "deny"), where);
            ObjectName mbean = parseName(rc.string("mbean", null), where);
            List<Glob> members = new ArrayList<>();
            for (String key : List.of("attributes", "operations", "members")) {
                rc.strings(key).forEach(g -> members.add(Glob.of(g)));
            }
            Set<String> roles = new LinkedHashSet<>(rc.strings("roles"));
            if (isAllow && roles.isEmpty()) {
                throw new ConfigException(where + ": allow rules must name the roles they apply to");
            }
            Rule rule = new Rule(isAllow ? Rule.Effect.ALLOW : Rule.Effect.DENY, roles, ops, mbean, members);
            (isAllow ? allow : deny).add(rule);
        }
        return new AccessPolicy(readRoles, deny, allow);
    }

    public Decision decide(RocotoIdentity identity, Target target) {
        if (!identity.inScope(target.mbean())) {
            return Decision.deny("outside the MBean scope of the credential");
        }
        String blocked = Denylist.blocked(target);
        if (blocked != null) {
            return Decision.deny("built-in deny list: " + blocked);
        }
        for (Rule r : denyRules) {
            if (r.matches(identity, target)) {
                return Decision.deny("deny rule for " + r.mbean());
            }
        }
        for (Rule r : allowRules) {
            if (r.matches(identity, target)) {
                return Decision.allow("allow rule for " + r.mbean());
            }
        }
        if (!target.operation().isMutating() && identity.hasAnyRole(readRoles)) {
            return Decision.allow("default read access");
        }
        return Decision.deny(target.operation().isMutating()
                ? "write/exec requires an explicit allow rule"
                : "no role with read access");
    }

    /** All role names the policy refers to – the container is asked for exactly these. */
    public Set<String> knownRoles() {
        Set<String> roles = new LinkedHashSet<>(readRoles);
        roles.addAll(List.of(ROLE_READER, ROLE_OPERATOR, ROLE_ADMIN));
        denyRules.forEach(r -> roles.addAll(r.roles()));
        allowRules.forEach(r -> roles.addAll(r.roles()));
        return roles;
    }

    private static Set<Operation> parseOperations(List<String> names, String where) {
        Set<Operation> ops = EnumSet.noneOf(Operation.class);
        for (String n : names) {
            if (n.equals("*") || n.equalsIgnoreCase("all")) {
                return EnumSet.allOf(Operation.class);
            }
            try {
                Operation op = Operation.valueOf(n.toUpperCase(Locale.ROOT));
                ops.add(op);
                if (op == Operation.READ) {
                    // Hiding values but listing them (or vice versa) is never what an admin means.
                    ops.add(Operation.LIST);
                    ops.add(Operation.SEARCH);
                }
            } catch (IllegalArgumentException e) {
                throw new ConfigException(where + ": unknown operation '" + n + "'");
            }
        }
        if (ops.isEmpty()) {
            throw new ConfigException(where + ": no operations given");
        }
        return ops;
    }

    private static ObjectName parseName(String name, String where) {
        if (name == null) {
            throw new ConfigException(where + ": 'mbean' is required");
        }
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            throw new ConfigException(where + ": invalid ObjectName '" + name + "'");
        }
    }
}
