package ru.koren.max.application;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bundled file must hold the Ministry's root and nothing pretending to be it: the fingerprint is
 * the one published for Russian Trusted Root CA. The JDK's own roots stay, so hosts on ordinary
 * certificates keep working.
 */
class MaxTlsTest {

    static final String RUSSIAN_TRUSTED_ROOT_SHA256 =
        "D26D2D0231B7C39F92CC738512BA54103519E4405D68B5BD703E9788CA8ECF31";

    @Test
    void theBundledRootIsTheMinistrysAndTheJdkRootsStay() throws Exception {
        List<String> fingerprints = new ArrayList<>();
        for (X509Certificate certificate : MaxTls.bundled()) {
            fingerprints.add(HexFormat.of().withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        }

        assertTrue(fingerprints.contains(RUSSIAN_TRUSTED_ROOT_SHA256), fingerprints.toString());
        assertFalse(MaxTls.jdkDefaults().isEmpty());
        assertNotNull(MaxTls.sslContext());
    }
}
