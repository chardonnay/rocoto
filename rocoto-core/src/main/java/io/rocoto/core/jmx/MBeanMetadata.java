package io.rocoto.core.jmx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.management.Descriptor;
import javax.management.DescriptorRead;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanParameterInfo;
import javax.management.openmbean.OpenType;

import io.rocoto.core.jmx.MBeanAccessException.Kind;

/** Lookups in {@link MBeanInfo}: attributes, operation overload resolution and open types. */
public final class MBeanMetadata {

    private MBeanMetadata() {
    }

    public static MBeanAttributeInfo attribute(MBeanInfo info, String name) {
        for (MBeanAttributeInfo a : info.getAttributes()) {
            if (a.getName().equals(name)) {
                return a;
            }
        }
        throw new MBeanAccessException(Kind.ATTRIBUTE_NOT_FOUND, "No such attribute: " + name);
    }

    /**
     * Finds the operation to call.
     *
     * @param signature explicit parameter types, or {@code null} to resolve by name and argument count
     */
    public static MBeanOperationInfo operation(MBeanInfo info, String name, List<String> signature, int argCount) {
        List<MBeanOperationInfo> candidates = new ArrayList<>();
        for (MBeanOperationInfo op : info.getOperations()) {
            if (!op.getName().equals(name)) {
                continue;
            }
            if (signature != null) {
                if (signature.equals(signatureOf(op))) {
                    return op;
                }
            } else if (op.getSignature().length == argCount) {
                candidates.add(op);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw new MBeanAccessException(Kind.OPERATION_NOT_FOUND,
                    "No operation " + name + (signature != null ? signature : " with " + argCount + " argument(s)"));
        }
        throw new MBeanAccessException(Kind.AMBIGUOUS_OPERATION,
                "Operation " + name + " is overloaded; specify the signature. Candidates: "
                        + candidates.stream().map(MBeanMetadata::signatureOf).toList());
    }

    public static List<String> signatureOf(MBeanOperationInfo op) {
        return Arrays.stream(op.getSignature()).map(MBeanParameterInfo::getType).toList();
    }

    public static OpenType<?> openType(MBeanAttributeInfo attribute) {
        if (attribute instanceof OpenMBeanAttributeInfo open) {
            return open.getOpenType();
        }
        return fromDescriptor(attribute);
    }

    public static OpenType<?> openType(MBeanParameterInfo parameter) {
        if (parameter instanceof OpenMBeanParameterInfo open) {
            return open.getOpenType();
        }
        return fromDescriptor(parameter);
    }

    private static OpenType<?> fromDescriptor(DescriptorRead feature) {
        Descriptor d = feature.getDescriptor();
        Object openType = d != null ? d.getFieldValue("openType") : null;
        return openType instanceof OpenType<?> ot ? ot : null;
    }
}
