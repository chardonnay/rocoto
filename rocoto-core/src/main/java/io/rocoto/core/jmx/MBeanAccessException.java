package io.rocoto.core.jmx;

/** Failure while talking to an MBeanServer, classified so callers can map it to a response status. */
public class MBeanAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        MBEAN_NOT_FOUND,
        ATTRIBUTE_NOT_FOUND,
        OPERATION_NOT_FOUND,
        AMBIGUOUS_OPERATION,
        INVALID_VALUE,
        /** The MBeanServer itself (e.g. WildFly management RBAC) refused the call. */
        FORBIDDEN_BY_SERVER,
        FAILED
    }

    private final Kind kind;

    public MBeanAccessException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public MBeanAccessException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
