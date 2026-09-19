package io.rocoto.undertow;

import java.lang.instrument.Instrumentation;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.rocoto.embedded.EmbeddedConfig;

/**
 * Java agent: {@code -javaagent:rocoto-undertow-<version>-all.jar=config=/etc/rocoto/rocoto.yaml,port=8778}
 * starts Rocoto inside any JVM. A failure is logged and never stops the application.
 */
public final class RocotoUndertowAgent {

    private static final Logger LOG = Logger.getLogger(RocotoUndertowAgent.class.getName());

    private RocotoUndertowAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        Thread thread = new Thread(() -> {
            try {
                EmbeddedConfig.applyAgentArguments(arguments);
                RocotoUndertowServer.start(true);
            } catch (RuntimeException | LinkageError e) {
                LOG.log(Level.SEVERE, "The Rocoto agent did not start: " + e.getMessage(), e);
            }
        }, "rocoto-agent-start");
        thread.setDaemon(true);
        thread.start();
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        premain(arguments, instrumentation);
    }
}
