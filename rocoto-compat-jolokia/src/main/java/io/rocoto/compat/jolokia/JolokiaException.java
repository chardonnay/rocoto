package io.rocoto.compat.jolokia;

/** An error in Jolokia's terms: status plus the Java exception class name clients switch on. */
class JolokiaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String errorType;

    JolokiaException(int status, String errorType, String message) {
        super(message);
        this.status = status;
        this.errorType = errorType;
    }

    int status() {
        return status;
    }

    String errorType() {
        return errorType;
    }
}
