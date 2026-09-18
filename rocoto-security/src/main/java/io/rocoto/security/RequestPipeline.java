package io.rocoto.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ObjectName;

import io.rocoto.core.convert.ArgumentConverter;
import io.rocoto.core.convert.ValueConverter;
import io.rocoto.core.jmx.MBeanAccess;
import io.rocoto.core.jmx.MBeanAccessException;
import io.rocoto.core.jmx.MBeanDescriber;
import io.rocoto.core.jmx.MBeanMetadata;
import io.rocoto.core.request.Target;
import io.rocoto.security.audit.AuditEvent;
import io.rocoto.security.audit.AuditEvent.Outcome;
import io.rocoto.security.audit.AuditLogger;
import io.rocoto.security.policy.AccessPolicy;
import io.rocoto.security.policy.Decision;
import io.rocoto.security.policy.ValueMasker;

/**
 * The single path from an authenticated caller to JMX:
 * <pre>policy -&gt; execute -&gt; mask -&gt; audit</pre>
 * Every endpoint (REST, Jolokia compatibility, metrics, streaming) uses this class and nothing
 * else touches {@link MBeanAccess}. Values are returned as raw Java objects with attribute-level
 * masking applied; convert them with a converter from {@link #converter()} so nested keys are
 * masked as well.
 */
public final class RequestPipeline {

    private final MBeanAccess access;
    private final AccessPolicy policy;
    private final ValueMasker masker;
    private final AuditLogger audit;

    public RequestPipeline(MBeanAccess access, AccessPolicy policy, ValueMasker masker, AuditLogger audit) {
        this.access = access;
        this.policy = policy;
        this.masker = masker;
        this.audit = audit;
    }

    /** A converter builder that is already wired to the masker. */
    public ValueConverter.Builder converter() {
        return ValueConverter.builder().maskedKey(masker::isMasked);
    }

    public AccessPolicy policy() {
        return policy;
    }

    // ---- search / list -------------------------------------------------------------------

    /** MBeans matching the pattern that the caller is allowed to see. */
    public Set<ObjectName> search(CallContext ctx, ObjectName pattern) {
        Set<ObjectName> visible = new TreeSet<>();
        for (ObjectName name : access.query(pattern)) {
            if (policy.decide(ctx.identity(), Target.list(name)).allowed()) {
                visible.add(name);
            }
        }
        return visible;
    }

    /** Metadata of one MBean, reduced to the attributes the caller may read. */
    public Map<String, Object> describe(CallContext ctx, ObjectName name) {
        check(ctx, Target.list(name), null);
        return filterDescription(ctx, name, access.info(name));
    }

    /** Raw metadata for callers that render their own format (Jolokia compatibility). */
    public MBeanInfo info(CallContext ctx, ObjectName name) {
        check(ctx, Target.list(name), null);
        return access.info(name);
    }

    public boolean mayRead(CallContext ctx, ObjectName name, String attribute) {
        return policy.decide(ctx.identity(), Target.read(name, attribute)).allowed();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterDescription(CallContext ctx, ObjectName name, MBeanInfo info) {
        Map<String, Object> d = MBeanDescriber.describe(info);
        Map<String, Object> attributes = (Map<String, Object>) d.get("attributes");
        attributes.keySet().removeIf(a -> !mayRead(ctx, name, a));
        for (Map.Entry<String, Object> e : attributes.entrySet()) {
            if (masker.isMasked(e.getKey())) {
                ((Map<String, Object>) e.getValue()).put("masked", true);
            }
        }
        return d;
    }

    // ---- read ----------------------------------------------------------------------------

    public Object read(CallContext ctx, ObjectName name, String attribute) {
        Target target = Target.read(name, attribute);
        check(ctx, target, null);
        if (masker.isMasked(attribute)) {
            // Make sure the attribute exists so masked and unknown attributes remain distinguishable.
            MBeanMetadata.attribute(access.info(name), attribute);
            return ValueConverter.MASK;
        }
        Object value = access.getAttribute(name, attribute);
        if (audit.auditReads()) {
            record(ctx, target, Outcome.ALLOWED, null, null);
        }
        return value;
    }

    /**
     * All readable attributes of one MBean the caller has access to. Attributes that are denied
     * or fail to read are left out (their names are returned in {@code errors}).
     */
    public Map<String, Object> readAll(CallContext ctx, ObjectName name, Map<String, String> errors) {
        check(ctx, Target.list(name), null);
        Map<String, Object> values = new LinkedHashMap<>();
        for (MBeanAttributeInfo a : access.info(name).getAttributes()) {
            if (!a.isReadable() || !mayRead(ctx, name, a.getName())) {
                continue;
            }
            if (masker.isMasked(a.getName())) {
                values.put(a.getName(), ValueConverter.MASK);
                continue;
            }
            try {
                values.put(a.getName(), access.getAttribute(name, a.getName()));
            } catch (MBeanAccessException e) {
                if (errors != null) {
                    errors.put(a.getName(), e.getMessage());
                }
            }
        }
        return values;
    }

    // ---- write ---------------------------------------------------------------------------

    /**
     * @param jsonValue parsed JSON value
     * @return the previous value (masked if the attribute is sensitive), or {@code null} if it is not readable
     */
    public Object write(CallContext ctx, ObjectName name, String attribute, Object jsonValue) {
        Target target = Target.write(name, attribute);
        boolean masked = masker.isMasked(attribute);
        Object auditArgs = masked ? ValueConverter.MASK : jsonValue;
        check(ctx, target, auditArgs);
        try {
            MBeanAttributeInfo info = MBeanMetadata.attribute(access.info(name), attribute);
            if (!info.isWritable()) {
                throw new MBeanAccessException(MBeanAccessException.Kind.INVALID_VALUE,
                        "Attribute " + attribute + " is read-only");
            }
            Object value = ArgumentConverter.convert(jsonValue, info.getType(), MBeanMetadata.openType(info));
            Object previous = null;
            if (info.isReadable()) {
                previous = masked ? ValueConverter.MASK : access.getAttribute(name, attribute);
            }
            access.setAttribute(name, attribute, value);
            record(ctx, target, Outcome.ALLOWED, null, auditArgs);
            return previous;
        } catch (RuntimeException e) {
            record(ctx, target, Outcome.FAILED, e.getMessage(), auditArgs);
            throw e;
        }
    }

    // ---- exec ----------------------------------------------------------------------------

    /**
     * @param signature explicit parameter types or {@code null} to resolve by argument count
     * @param jsonArgs  parsed JSON arguments
     */
    public Object exec(CallContext ctx, ObjectName name, String operation, List<String> signature,
            List<Object> jsonArgs) {
        Target target = Target.exec(name, operation);
        List<Object> args = jsonArgs == null ? List.of() : jsonArgs;
        check(ctx, target, audit.auditExecArguments() ? args : null);
        try {
            MBeanOperationInfo op = MBeanMetadata.operation(access.info(name), operation, signature, args.size());
            MBeanParameterInfo[] params = op.getSignature();
            if (params.length != args.size()) {
                throw new MBeanAccessException(MBeanAccessException.Kind.INVALID_VALUE,
                        "Operation " + operation + " expects " + params.length + " argument(s)");
            }
            Object[] converted = new Object[params.length];
            List<Object> auditArgs = new ArrayList<>(params.length);
            for (int i = 0; i < params.length; i++) {
                converted[i] = ArgumentConverter.convert(args.get(i), params[i].getType(),
                        MBeanMetadata.openType(params[i]));
                auditArgs.add(masker.isMasked(params[i].getName()) ? ValueConverter.MASK : args.get(i));
            }
            Object result = access.invoke(name, operation, converted,
                    MBeanMetadata.signatureOf(op).toArray(String[]::new));
            record(ctx, target, Outcome.ALLOWED, null, audit.auditExecArguments() ? auditArgs : null);
            return result;
        } catch (RuntimeException e) {
            record(ctx, target, Outcome.FAILED, e.getMessage(), null);
            throw e;
        }
    }

    // ---- internals -----------------------------------------------------------------------

    private void check(CallContext ctx, Target target, Object auditArgs) {
        Decision decision = policy.decide(ctx.identity(), target);
        if (!decision.allowed()) {
            record(ctx, target, Outcome.DENIED, decision.reason(), auditArgs);
            throw new AccessDeniedException();
        }
    }

    private void record(CallContext ctx, Target target, Outcome outcome, String reason, Object args) {
        Object safeArgs = args == null ? null : converter().maxDepth(4).maxCollectionSize(32).build().convert(args);
        audit.log(new AuditEvent(Instant.now(), ctx.identity().name(), ctx.identity().source(),
                ctx.remoteAddress(), ctx.channel(), target.operation().name(), target.mbean().getCanonicalName(),
                target.member(), outcome, reason, safeArgs));
    }
}
