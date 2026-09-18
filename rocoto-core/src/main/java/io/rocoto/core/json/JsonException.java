package io.rocoto.core.json;

/** Thrown when JSON input is malformed or exceeds the configured limits. */
public class JsonException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
