package io.rocoto.embedded;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

/**
 * One HTTP request/response as seen by {@link RocotoEngine}; implemented by the server adapters
 * (Undertow, Netty). All methods are called from a worker thread, never from an I/O thread, and
 * may block.
 */
public interface Exchange {

    String method();

    /** The raw (still percent-encoded) request path without the query string. */
    String rawPath();

    /** The raw query string, or {@code null}. */
    String rawQuery();

    /** Request headers; the lookup of names must be case-insensitive. */
    Map<String, List<String>> headers();

    InputStream body();

    String protocol();

    String scheme();

    String serverName();

    int serverPort();

    String remoteAddress();

    /** {@code true} if the connection is protected by TLS. */
    boolean secure();

    /** The validated client certificate chain of a TLS connection, or {@code null}. */
    X509Certificate[] clientCertificates();

    /**
     * Sends status line and headers. {@code contentLength} is the exact length of the body that
     * follows, or {@code -1} for a streamed body of unknown length (e.g. Server-Sent Events).
     */
    void begin(int status, Map<String, List<String>> headers, long contentLength) throws IOException;

    /** Sends a part of the body and flushes it to the client. */
    void write(byte[] buffer, int offset, int length) throws IOException;

    /** Completes the response. Must be idempotent. */
    void end();

    /** Registers a callback for the case that the client goes away before {@link #end()}. */
    void onClosed(Runnable callback);
}
