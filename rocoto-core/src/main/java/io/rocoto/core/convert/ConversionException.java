package io.rocoto.core.convert;

/** A client supplied value cannot be converted to the type the MBean expects. */
public class ConversionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public ConversionException(String message) {
        super(message);
    }
}
