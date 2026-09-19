package io.rocoto.netty;

import java.lang.instrument.Instrumentation;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.rocoto.embedded.EmbeddedConfig;

/**
 * Java agent: {@code -javaagent:rocoto-netty-<version>-all.jar=config=/etc/rocoto/rocoto.yaml,port=8778}
 * starts Rocoto inside any JVM. A failure is logged and never stops the application.
 */
public final class RocotoNettyAgent {

    private static final Logger LOG = Logger.getLogger(RocotoNettyAgent.class.getName());

    private RocotoNettyAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        Thread thread = new Thread(() -> {
            try {
                EmbeddedConfig.applyAgentArguments(arguments);
                RocotoNettyServer.start(true);
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
