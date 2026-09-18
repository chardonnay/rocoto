package io.rocoto.security.tls;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertPathValidatorException.BasicReason;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.PKIXRevocationChecker.Option;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a certificate chain against trust anchors including revocation: OCSP first, CRL as
 * fallback (JDK {@link PKIXRevocationChecker}). Positive results are cached for a short time so
 * that not every request triggers an OCSP round trip.
 */
public final class RevocationChecker {

    public record Result(boolean valid, String detail) {
    }

    private final Set<TrustAnchor> anchors;
    private final RevocationMode mode;
    private final URI responderOverride;
    private final long cacheTtlMillis;
    private final Map<String, Long> validUntil = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 1024;
                }
            });

    public RevocationChecker(Set<TrustAnchor> anchors, RevocationMode mode, URI responderOverride,
            Duration cacheTtl) {
        this.anchors = anchors;
        this.mode = mode;
        this.responderOverride = responderOverride;
        this.cacheTtlMillis = cacheTtl.toMillis();
    }

    public RevocationMode mode() {
        return mode;
    }

    public Result check(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) {
            return new Result(false, "no certificate");
        }
        String cacheKey = chain[0].getIssuerX500Principal().getName() + "#" + chain[0].getSerialNumber();
        Long cached = validUntil.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached > now) {
            return new Result(true, "cached");
        }
        try {
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            PKIXParameters params = new PKIXParameters(anchors);
            if (mode == RevocationMode.OFF) {
                params.setRevocationEnabled(false);
            } else {
                PKIXRevocationChecker checker = (PKIXRevocationChecker) validator.getRevocationChecker();
                Set<Option> options = EnumSet.noneOf(Option.class);
                if (mode == RevocationMode.SOFT_FAIL) {
                    options.add(Option.SOFT_FAIL);
                }
                checker.setOptions(options);
                if (responderOverride != null) {
                    checker.setOcspResponder(responderOverride);
                }
                params.setRevocationEnabled(true);
                params.addCertPathChecker(checker);
            }
            validator.validate(toPath(chain), params);
            validUntil.put(cacheKey, now + cacheTtlMillis);
            return new Result(true, "valid");
        } catch (CertPathValidatorException e) {
            String reason = e.getReason() == BasicReason.REVOKED ? "certificate revoked"
                    : e.getReason() == BasicReason.UNDETERMINED_REVOCATION_STATUS
                        ? "revocation status could not be determined"
                        : "certificate path invalid: " + e.getMessage();
            return new Result(false, reason);
        } catch (GeneralSecurityException e) {
            return new Result(false, "certificate validation failed: " + e.getMessage());
        }
    }

    /** Builds the path without the trust anchor itself, as PKIX validation requires. */
    private CertPath toPath(X509Certificate[] chain) throws GeneralSecurityException {
        List<X509Certificate> path = new ArrayList<>(Arrays.asList(chain));
        path.removeIf(c -> anchors.stream().anyMatch(a -> c.equals(a.getTrustedCert())));
        return CertificateFactory.getInstance("X.509").generateCertPath(path);
    }
}
