package io.rocoto.security.tls;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertificateException;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.PKIXRevocationChecker.Option;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;

/**
 * TLS for connections Rocoto opens itself (JWKS download, OTLP push).
 *
 * <p>The JDK client requests stapled OCSP responses by default
 * ({@code jdk.tls.client.enableStatusRequestExtension}); with revocation checking enabled the
 * stapled response is validated during the handshake. {@code requireStapling} additionally
 * rejects servers that do not staple at all (must-staple behaviour).
 */
public final class OutboundTls {

    private OutboundTls() {
    }

    /** @param tls the {@code tls.outbound} section of rocoto.yaml */
    public static SSLContext context(Config tls) {
        RevocationMode mode = RevocationMode.parse(tls.string("revocation", "soft-fail"), "tls.outbound.revocation");
        boolean requireStapling = tls.bool("requireStapling", false);
        try {
            KeyStore trustStore = tls.has("truststore") ? TrustMaterial.load(tls, "tls.outbound") : null;
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            if (mode == RevocationMode.OFF) {
                tmf.init(trustStore);
            } else {
                PKIXBuilderParameters params = new PKIXBuilderParameters(
                        TrustMaterial.anchors(tls, "tls.outbound"), new X509CertSelector());
                PKIXRevocationChecker checker =
                        (PKIXRevocationChecker) CertPathBuilder.getInstance("PKIX").getRevocationChecker();
                Set<Option> options = EnumSet.noneOf(Option.class);
                if (mode == RevocationMode.SOFT_FAIL) {
                    options.add(Option.SOFT_FAIL);
                }
                checker.setOptions(options);
                params.addCertPathChecker(checker);
                params.setRevocationEnabled(true);
                tmf.init(new CertPathTrustManagerParameters(params));
            }
            TrustManager[] managers = tmf.getTrustManagers();
            if (requireStapling) {
                for (int i = 0; i < managers.length; i++) {
                    if (managers[i] instanceof X509ExtendedTrustManager x) {
                        managers[i] = new RequireStapling(x);
                    }
                }
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new ConfigException("tls.outbound: cannot initialise TLS", e);
        }
    }

    /** Stapled OCSP responses of a handshake session; empty if the server did not staple. */
    public static List<byte[]> stapledResponses(SSLSession session) {
        return session instanceof ExtendedSSLSession ext ? ext.getStatusResponses() : List.of();
    }

    /** Delegating trust manager that fails the handshake when no OCSP response was stapled. */
    private static final class RequireStapling extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager delegate;

        RequireStapling(X509ExtendedTrustManager delegate) {
            this.delegate = delegate;
        }

        private static void requireStapled(SSLSession session) throws CertificateException {
            if (session == null || stapledResponses(session).isEmpty()) {
                throw new CertificateException("Server did not staple an OCSP response (tls.outbound.requireStapling)");
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, socket);
            requireStapled(socket instanceof SSLSocket s ? s.getHandshakeSession() : null);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, engine);
            requireStapled(engine != null ? engine.getHandshakeSession() : null);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Stapling cannot be verified without a TLS session");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
