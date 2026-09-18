package io.rocoto.security.auth;

import java.security.cert.X509Certificate;

/**
 * The parts of an HTTP request that matter for authentication, detached from the servlet API.
 *
 * @param authorization value of the {@code Authorization} header or {@code null}
 * @param clientCertificates verified client certificate chain from the TLS layer or {@code null}
 * @param secure whether the request arrived over TLS
 */
public record AuthRequest(String authorization, X509Certificate[] clientCertificates, boolean secure,
        String remoteAddress) {
}
