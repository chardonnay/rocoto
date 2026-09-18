package io.rocoto.core.config;

/** Invalid or unreadable configuration. Rocoto refuses to start rather than guess. */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
