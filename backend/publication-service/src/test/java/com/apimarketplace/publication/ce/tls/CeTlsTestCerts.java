package com.apimarketplace.publication.ce.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Throwaway X.509 test fixtures (generated with openssl, 10y validity):
 * <ul>
 *   <li>{@link #CA_PEM} - a self-signed root ("Test Intercept Root CA"), subject == issuer.</li>
 *   <li>{@link #LEAF_PEM} - a leaf signed by that CA, subject != issuer.</li>
 * </ul>
 * Not secrets - purely for trust-store / chain-ordering assertions.
 */
final class CeTlsTestCerts {

    private CeTlsTestCerts() {
    }

    static final String CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDIzCCAgugAwIBAgIUd9NFecR84hr6IYloSB+VDqRzWYwwDQYJKoZIhvcNAQEL
            BQAwITEfMB0GA1UEAwwWVGVzdCBJbnRlcmNlcHQgUm9vdCBDQTAeFw0yNjA2MTgx
            MzAwNThaFw0zNjA2MTUxMzAwNThaMCExHzAdBgNVBAMMFlRlc3QgSW50ZXJjZXB0
            IFJvb3QgQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCoWSKeWUkK
            rOaCw1vpND6dpy28UnZSaVR2eeUyJH5p+LD+rUAh1/f9gW0KkpQVd1iz5dihPKm2
            +L10TZ7mJ8PZjYY7F/XsebH23dftcuCRNSLMF4Gn+gVrkaaLQeqPVQnr+Ml17EO2
            7SJxuc2anVh8DW1ZhZFKtt+3GNNj/Zo/vlfXe1yB3pskopo03A8HVf1R9u0wezY3
            DOQbB35ocBM/kyfZ82OMnqyiXfRjv7Db4mwu80z8xO1CRwzTTTig65GVyQFuYwi5
            5QgtctoEu8tBf9S2VpeHzIlw+BksWPSaOTtF5SVNNoUaFGLgPtuDUppGlFke7N9X
            lXedteubl7/nAgMBAAGjUzBRMB0GA1UdDgQWBBQBYNjCw7ovJDn8V1eh/QCyJMxS
            szAfBgNVHSMEGDAWgBQBYNjCw7ovJDn8V1eh/QCyJMxSszAPBgNVHRMBAf8EBTAD
            AQH/MA0GCSqGSIb3DQEBCwUAA4IBAQA+EURwFsDTNZmlnNLKuW2heuXuqcxqcmRh
            KopZGXWAOIlMQ028ZKBwx2YqUsGklqd2T2ABz+1F5QtkBFlE24pVDCJBeHgEzcyq
            Z1Ict4NjvUyL4V/dy7YDyxvZyFTWaASduyS2lf8vYgFQxGn/zIvsOQvcRsggMiVc
            YXAPl2SuhZPNdKX9G5DvcK11zY7i+gZ3VMpQr5uCkvFUa5cMtmNBEluiHWA0TOf/
            JogtWJ4gCzyDeNzP5H6WsgdMDbFVEHr+cmeq/x+ekMLKhODWCzK4pvqgD2n/3DyY
            34SHeoV9x5rKFT6vkKXlRlTDsh3dODmv28BdL2349W7eK02gAffy
            -----END CERTIFICATE-----
            """;

    static final String LEAF_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDDTCCAfWgAwIBAgIULeSq/OPyA4DHvYVNaV8VfYponXUwDQYJKoZIhvcNAQEL
            BQAwITEfMB0GA1UEAwwWVGVzdCBJbnRlcmNlcHQgUm9vdCBDQTAeFw0yNjA2MTgx
            MzAxMTlaFw0zNjA2MTUxMzAxMTlaMBwxGjAYBgNVBAMMEWxlYWYuZXhhbXBsZS50
            ZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAp1v32dcj0Doqs+br
            7IWgVexC6JnYWvhHV/hPlebl0mpRc//KZMYq543waB3+80bDnTtHnhIaTLnb/0Lq
            4Yy3uXSUWb0VTOP5ya3UD7FK6ZGTl2usxNnJJIcQwMNtbt7XhCc+W/3MlfsgOYDS
            rMx7pmE1DGwByU62ifh+VMJldTjtipxWL80DFknGqBfWQVlElladq3bFTRnKbM4H
            fQXbFPgHYuC0tsMKYOGU31bcHK8RhX0QhyXXxlgBxrAHba4db7zs77Je8c8Jy0L1
            NPRdY+b5Putox6WmXLVOsolzyJ2quNQz2c6k/HK+VXHtO3dj6OOT6fMiqGLsfwb6
            y0SAfQIDAQABo0IwQDAdBgNVHQ4EFgQUBUAg0xYL8gj+Djr7PSEvofup0KswHwYD
            VR0jBBgwFoAUAWDYwsO6LyQ5/FdXof0AsiTMUrMwDQYJKoZIhvcNAQELBQADggEB
            AFchjFt1oBfiJazq4f8XRlkD/M4wCVX1586rNKNt2XEXI6wXwGAgHEt+FBbr5J5u
            /LoVMUcRWby3hdCVpXjmIWfK2mLOlOvRgKEH4aIaZc0lhdKkTFjU91H1JxucB0Su
            f52Bf1tG4gYAB6mmlUlm1PmsRWgFfnw8Wljn3h30wUi95WMWyBPySWl0Qlua13zF
            xzdH2BbISQ5Tu2q11iE3ngeFGBwvFUv78MysJMzkIWIi7lJi0JCFvDaHjTC+Vsjx
            bWzgpw04i8G+CvUDsOTdjYVC2wxSU9zpa44yOgY+reJpKM1O2EnEoNlm0PcstnUa
            k7MjWgG3p24hwbnlHJc1TrM=
            -----END CERTIFICATE-----
            """;

    static X509Certificate ca() throws Exception {
        return CeCustomTrustStore.parseCertificate(CA_PEM);
    }

    static X509Certificate leaf() throws Exception {
        return CeCustomTrustStore.parseCertificate(LEAF_PEM);
    }

    /** PKCS12 keystore (leaf key + leaf cert + CA chain), pass "changeit" - for a test TLS server. */
    private static final String SERVER_P12_BASE64 =
            "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";

    /** SSLContext that presents the leaf (+ CA chain) - for a throwaway in-test TLS server. */
    static SSLContext serverSslContext() throws Exception {
        char[] pass = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(Base64.getDecoder().decode(SERVER_P12_BASE64)), pass);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }
}
