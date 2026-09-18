package io.rocoto.security.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;

/** Trust anchors: a configured PKCS#12/JKS trust store, or the JDK default trust store. */
public final class TrustMaterial {

    private TrustMaterial() {
    }

    /**
     * @param section configuration section with optional {@code truststore}, {@code truststorePassword}
     *                and {@code truststoreType}
     */
    public static Set<TrustAnchor> anchors(Config section, String path) {
        Set<TrustAnchor> anchors = new HashSet<>();
        try {
            String file = section.string("truststore", null);
            if (file == null) {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((KeyStore) null);
                for (TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager x) {
                        for (X509Certificate c : x.getAcceptedIssuers()) {
                            anchors.add(new TrustAnchor(c, null));
                        }
                    }
                }
            } else {
                KeyStore ks = load(section, path);
                for (String alias : Collections.list(ks.aliases())) {
                    if (ks.getCertificate(alias) instanceof X509Certificate c) {
                        anchors.add(new TrustAnchor(c, null));
                    }
                }
            }
        } catch (GeneralSecurityException e) {
            throw new ConfigException(path + ": cannot load trust anchors", e);
        }
        if (anchors.isEmpty()) {
            throw new ConfigException(path + ": trust store contains no certificates");
        }
        return anchors;
    }

    public static KeyStore load(Config section, String path) {
        String file = section.string("truststore", null);
        String password = section.string("truststorePassword", null);
        String type = section.string("truststoreType", "PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(file))) {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(in, password == null ? null : password.toCharArray());
            return ks;
        } catch (IOException | GeneralSecurityException e) {
            throw new ConfigException(path + ".truststore: cannot load " + file, e);
        }
    }
}
