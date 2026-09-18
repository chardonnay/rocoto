package io.rocoto.security.tls;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Looks at a TLS endpoint from the outside: certificate chain, expiry and whether the server
 * staples an OCSP response. Used by {@code rocoto-tools tls check} and by the optional self probe
 * that turns the result into metrics and health warnings.
 *
 * <p>The probe accepts any certificate – it transfers no data and its purpose is to report what
 * the server presents, including broken or expired chains.
 */
public final class TlsProbe {

    public record CertificateInfo(String subject, String issuer, Instant notBefore, Instant notAfter) {
    }

    public record Result(String protocol, String cipherSuite, List<CertificateInfo> chain, boolean stapled,
            OcspResponseInfo ocsp) {

        public Instant earliestExpiry() {
            return chain.stream().map(CertificateInfo::notAfter).min(Instant::compareTo).orElse(null);
        }
    }

    private TlsProbe() {
    }

    public static Result probe(String host, int port, int timeoutMillis) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new AcceptAll()}, null);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress(host, port), timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
                SSLParameters params = socket.getSSLParameters();
                if (!host.matches("[0-9.:a-fA-F]+")) {
                    params.setServerNames(List.of(new SNIHostName(host)));
                }
                socket.setSSLParameters(params);
                socket.startHandshake();
                List<CertificateInfo> chain = new ArrayList<>();
                for (Certificate c : socket.getSession().getPeerCertificates()) {
                    if (c instanceof X509Certificate x) {
                        chain.add(new CertificateInfo(x.getSubjectX500Principal().getName(),
                                x.getIssuerX500Principal().getName(), x.getNotBefore().toInstant(),
                                x.getNotAfter().toInstant()));
                    }
                }
                List<byte[]> stapled = OutboundTls.stapledResponses(socket.getSession());
                return new Result(socket.getSession().getProtocol(), socket.getSession().getCipherSuite(), chain,
                        !stapled.isEmpty(), stapled.isEmpty() ? null : OcspResponseInfo.parse(stapled.get(0)));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot initialise TLS: " + e.getMessage(), e);
        }
    }

    private static final class AcceptAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // probe only
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // probe only: report whatever the server presents
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
