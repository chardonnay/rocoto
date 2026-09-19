package io.rocoto.embedded;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Locale;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.security.tls.TrustMaterial;

/**
 * Listener settings of the standalone servers and the Java agents ({@code embedded} in rocoto.yaml):
 * <pre>
 * embedded:
 *   host: 127.0.0.1          # default: loopback only
 *   port: 8778
 *   contextPath: /rocoto
 *   workerThreads: 8
 *   tls:                     # without it the listener is plain HTTP and Rocoto refuses credentials
 *     keystore: /etc/rocoto/server.p12
 *     keystorePassword: ${env:ROCOTO_KEYSTORE_PASSWORD}
 *     keystoreType: PKCS12
 *     clientAuth: none       # none | want | need; want/need use the trust store below
 *     truststore: /etc/rocoto/client-ca.p12
 *     truststorePassword: ...
 * </pre>
 */
public final class EmbeddedConfig {

    /** How the TLS listener asks for client certificates. */
    public enum ClientAuth { NONE, WANT, NEED }

    private final Config section;

    EmbeddedConfig(Config section) {
        this.section = section;
    }

    /** System property {@code rocoto.embedded.host} (set by the agent arguments) wins over rocoto.yaml. */
    public String host() {
        return System.getProperty("rocoto.embedded.host", section.string("host", "127.0.0.1"));
    }

    /** System property {@code rocoto.embedded.port} (set by the agent arguments) wins over rocoto.yaml. */
    public int port() {
        String override = System.getProperty("rocoto.embedded.port");
        try {
            return override != null ? Integer.parseInt(override.trim()) : section.integer("port", 8778);
        } catch (NumberFormatException e) {
            throw new ConfigException("rocoto.embedded.port is not a number: " + override);
        }
    }

    /**
     * Agent arguments {@code config=/path/rocoto.yaml,host=0.0.0.0,port=8778} become the system
     * properties {@code rocoto.config}, {@code rocoto.embedded.host} and {@code rocoto.embedded.port}.
     */
    public static void applyAgentArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return;
        }
        for (String pair : arguments.split(",")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair.trim() : pair.substring(0, eq).trim();
            String value = eq < 0 ? "" : pair.substring(eq + 1).trim();
            switch (key) {
                case "config" -> System.setProperty("rocoto.config", value);
                case "host" -> System.setProperty("rocoto.embedded.host", value);
                case "port" -> System.setProperty("rocoto.embedded.port", value);
                default -> throw new ConfigException("Unknown agent argument '" + key + "' (config, host, port)");
            }
        }
    }

    public String contextPath() {
        return section.string("contextPath", "/rocoto");
    }

    public int workerThreads() {
        return Math.max(2, section.integer("workerThreads", 8));
    }

    public boolean tlsEnabled() {
        return section.has("tls.keystore");
    }

    public ClientAuth clientAuth() {
        String value = section.string("tls.clientAuth", "none");
        try {
            return ClientAuth.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("embedded.tls.clientAuth must be one of none, want, need");
        }
    }

    /** @return the server side TLS context, or {@code null} if {@code embedded.tls.keystore} is not set */
    public SSLContext sslContext() {
        if (!tlsEnabled()) {
            return null;
        }
        Config tls = section.sub("tls");
        String file = tls.string("keystore", null);
        String password = tls.string("keystorePassword", null);
        char[] chars = password == null ? new char[0] : password.toCharArray();
        try (InputStream in = Files.newInputStream(Path.of(file))) {
            KeyStore keyStore = KeyStore.getInstance(tls.string("keystoreType", "PKCS12"));
            keyStore.load(in, chars);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, chars);
            TrustManager[] trust = null;
            if (clientAuth() != ClientAuth.NONE) {
                if (!tls.has("truststore")) {
                    throw new ConfigException("embedded.tls.clientAuth needs embedded.tls.truststore");
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(TrustMaterial.load(tls, "embedded.tls"));
                trust = tmf.getTrustManagers();
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), trust, null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new ConfigException("embedded.tls.keystore: cannot load " + file, e);
        }
    }
}
