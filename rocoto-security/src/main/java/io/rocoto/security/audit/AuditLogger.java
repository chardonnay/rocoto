package io.rocoto.security.audit;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.json.JsonWriter;

/**
 * Writes audit records as JSON lines. Records always go to the {@code io.rocoto.audit} logger
 * (and thereby into the application server's log); optionally they are also appended to a file.
 */
public class AuditLogger implements AutoCloseable {

    private static final Logger AUDIT = Logger.getLogger("io.rocoto.audit");
    private static final Logger LOG = Logger.getLogger(AuditLogger.class.getName());

    private final BufferedWriter file;
    private final boolean auditReads;
    private final boolean auditExecArguments;

    protected AuditLogger(BufferedWriter file, boolean auditReads, boolean auditExecArguments) {
        this.file = file;
        this.auditReads = auditReads;
        this.auditExecArguments = auditExecArguments;
    }

    /** @param audit the {@code audit} section of rocoto.yaml */
    public static AuditLogger fromConfig(Config audit) {
        String path = audit.string("file", null);
        BufferedWriter writer = null;
        if (path != null) {
            try {
                Path p = Path.of(path);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                writer = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // An unwritable audit trail is a configuration error, not something to ignore silently.
                throw new ConfigException("Cannot open audit file " + path, e);
            }
        }
        return new AuditLogger(writer, audit.bool("reads", false), audit.bool("execArguments", false));
    }

    /** Whether successful reads are recorded. Denials and all write/exec calls always are. */
    public boolean auditReads() {
        return auditReads;
    }

    /**
     * Whether operation arguments are recorded. Off by default: JMX rarely exposes parameter names,
     * so Rocoto cannot tell a password argument from a harmless one.
     */
    public boolean auditExecArguments() {
        return auditExecArguments;
    }

    public void log(AuditEvent event) {
        String line = JsonWriter.toJson(event.toMap());
        AUDIT.info(line);
        if (file != null) {
            synchronized (file) {
                try {
                    file.write(line);
                    file.newLine();
                    file.flush();
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Cannot write audit file", e);
                }
            }
        }
    }

    @Override
    public void close() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Closing audit file failed", e);
            }
        }
    }
}
