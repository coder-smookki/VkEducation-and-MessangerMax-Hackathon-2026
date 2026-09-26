package ru.koren.max.application;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * MAX asks its partners to trust the Russian Trusted Root CA of the Ministry of Digital Development.
 * It is added next to the JDK's own roots, for the MAX client only: nothing else in the process
 * trusts it, and certificate checking is never switched off. The bundled file was checked against
 * the published SHA-256 of the root (MaxTlsTest pins it), so a swapped file fails the build.
 */
public final class MaxTls {

    static final String BUNDLE = "certs/russian_trusted_ca.pem";

    private MaxTls() {
    }

    public static SSLContext sslContext() {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (X509Certificate root : jdkDefaults()) {
                store.setCertificateEntry("jdk-" + index++, root);
            }
            for (X509Certificate root : bundled()) {
                store.setCertificateEntry("ru-" + index++, root);
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException broken) {
            // Failing at startup is the point: a sender that cannot verify MAX must not start.
            throw new IllegalStateException("Cannot build the trust store for MAX", broken);
        }
    }

    static List<X509Certificate> jdkDefaults() throws GeneralSecurityException {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init((KeyStore) null);
        for (TrustManager manager : trust.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) return List.of(x509.getAcceptedIssuers());
        }
        throw new IllegalStateException("The JDK has no X.509 trust manager");
    }

    static List<X509Certificate> bundled() throws GeneralSecurityException, IOException {
        try (InputStream in = MaxTls.class.getClassLoader().getResourceAsStream(BUNDLE)) {
            if (in == null) throw new IllegalStateException("Missing " + BUNDLE);
            return CertificateFactory.getInstance("X.509").generateCertificates(in).stream()
                .map(X509Certificate.class::cast)
                .toList();
        }
    }
}
