package io.rocoto.core.request;

import javax.management.ObjectName;

/**
 * The thing an {@link Operation} is applied to: an MBean and, where applicable, one of its
 * attributes or operations. This is the unit the access policy decides on.
 *
 * @param member attribute name for READ/WRITE, operation name for EXEC, {@code null} otherwise
 */
public record Target(Operation operation, ObjectName mbean, String member) {

    public static Target read(ObjectName mbean, String attribute) {
        return new Target(Operation.READ, mbean, attribute);
    }

    public static Target write(ObjectName mbean, String attribute) {
        return new Target(Operation.WRITE, mbean, attribute);
    }

    public static Target exec(ObjectName mbean, String operation) {
        return new Target(Operation.EXEC, mbean, operation);
    }

    public static Target list(ObjectName mbean) {
        return new Target(Operation.LIST, mbean, null);
    }

    @Override
    public String toString() {
        return operation + " " + mbean + (member != null ? "#" + member : "");
    }
}
