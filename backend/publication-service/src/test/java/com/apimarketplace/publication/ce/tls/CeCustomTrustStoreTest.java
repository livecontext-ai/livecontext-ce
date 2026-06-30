package com.apimarketplace.publication.ce.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CeCustomTrustStore")
class CeCustomTrustStoreTest {

    @TempDir
    Path caDir;

    private CeCustomTrustStore store;

    // The store installs a JVM-global default SSL socket factory AND sets the
    // javax.net.ssl.trustStore* system properties on reload; save and restore both
    // so a test never leaks custom trust into the rest of the suite.
    private SSLSocketFactory savedHttpsFactory;
    private SSLContext savedDefaultContext;
    private String savedTrustStore;
    private String savedTrustStorePassword;
    private String savedTrustStoreType;

    @BeforeEach
    void setUp() throws Exception {
        savedHttpsFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        savedDefaultContext = SSLContext.getDefault();
        savedTrustStore = System.getProperty("javax.net.ssl.trustStore");
        savedTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        savedTrustStoreType = System.getProperty("javax.net.ssl.trustStoreType");
        store = new CeCustomTrustStore(caDir.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        HttpsURLConnection.setDefaultSSLSocketFactory(savedHttpsFactory);
        SSLContext.setDefault(savedDefaultContext);
        restoreProperty("javax.net.ssl.trustStore", savedTrustStore);
        restoreProperty("javax.net.ssl.trustStorePassword", savedTrustStorePassword);
        restoreProperty("javax.net.ssl.trustStoreType", savedTrustStoreType);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /**
     * Count the persisted CA files (the {@code <sha>.pem} entries) - excludes the generated
     * composite truststore ({@code .p12}) that the store also writes into the same dir.
     */
    private static long pemFileCount(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".pem")).count();
        }
    }

    @Test
    @DisplayName("Empty dir on init → no custom CAs, JVM global trust left untouched")
    void emptyDirIsNoOp() {
        store.reload();

        assertThat(store.listTrustedCas()).isEmpty();
        // No-op: the default factory must be exactly the one present before reload.
        assertThat(HttpsURLConnection.getDefaultSSLSocketFactory()).isSameAs(savedHttpsFactory);
    }

    @Test
    @DisplayName("addTrustedCa persists the PEM and surfaces the CA identity")
    void addTrustedCaPersistsAndLists() throws Exception {
        CeCustomTrustStore.TrustedCa added = store.addTrustedCa(CeTlsTestCerts.CA_PEM);

        assertThat(added.subject()).contains("Test Intercept Root CA");
        assertThat(added.sha256()).hasSize(64); // hex SHA-256
        // A file named after the fingerprint is written into the persistent dir.
        assertThat(pemFileCount(caDir)).isEqualTo(1);
        assertThat(store.listTrustedCas())
                .singleElement()
                .satisfies(ca -> {
                    assertThat(ca.subject()).contains("Test Intercept Root CA");
                    assertThat(ca.sha256()).isEqualTo(added.sha256());
                });
    }

    @Test
    @DisplayName("addTrustedCa installs a JVM-global socket factory (custom trust now active)")
    void addTrustedCaInstallsGlobalFactory() throws Exception {
        store.addTrustedCa(CeTlsTestCerts.CA_PEM);

        // Once a custom CA is trusted the global default must have been swapped
        // (the additive composite trust manager is now in effect).
        assertThat(HttpsURLConnection.getDefaultSSLSocketFactory()).isNotSameAs(savedHttpsFactory);
    }

    @Test
    @DisplayName("addTrustedCa is idempotent - the same CA is not duplicated")
    void addTrustedCaIdempotent() throws Exception {
        store.addTrustedCa(CeTlsTestCerts.CA_PEM);
        store.addTrustedCa(CeTlsTestCerts.CA_PEM);

        assertThat(pemFileCount(caDir)).isEqualTo(1);
        assertThat(store.listTrustedCas()).hasSize(1);
    }

    @Test
    @DisplayName("reload picks up a CA file dropped into the dir out-of-band")
    void reloadPicksUpDroppedFile() throws Exception {
        Files.writeString(caDir.resolve("corp.pem"), CeTlsTestCerts.CA_PEM);

        store.reload();

        assertThat(store.listTrustedCas())
                .singleElement()
                .satisfies(ca -> assertThat(ca.subject()).contains("Test Intercept Root CA"));
    }

    @Test
    @DisplayName("addTrustedCa rejects a value that is not a valid certificate")
    void rejectsInvalidCertificate() {
        assertThatThrownBy(() -> store.addTrustedCa("this is not a certificate"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    @DisplayName("parseCertificate reads the subject of a PEM certificate")
    void parsesSubject() throws Exception {
        var cert = CeCustomTrustStore.parseCertificate(CeTlsTestCerts.CA_PEM);
        assertThat(cert.getSubjectX500Principal().getName()).contains("Test Intercept Root CA");
    }

    // ── the additive-trust guarantee (the security crux) ────────────────────

    @Test
    @DisplayName("composite trust is ADDITIVE - installing a custom CA keeps every system anchor")
    void compositeTrustIsAdditive() throws Exception {
        int systemOnly = CeCustomTrustStore.buildCompositeTrustManager(List.of())
                .getAcceptedIssuers().length;
        int withCustom = CeCustomTrustStore.buildCompositeTrustManager(List.of(CeTlsTestCerts.ca()))
                .getAcceptedIssuers().length;

        assertThat(systemOnly).isGreaterThan(0); // the JVM's default anchors are present
        assertThat(withCustom).isEqualTo(systemOnly + 1); // custom CA is ADDED, nothing removed
    }

    @Test
    @DisplayName("composite trust accepts a chain that validates against the custom CA")
    void compositeAcceptsChainSignedByCustomCa() throws Exception {
        var tm = CeCustomTrustStore.buildCompositeTrustManager(List.of(CeTlsTestCerts.ca()));

        // [leaf, ca] chains to the trusted custom root → accepted (no exception thrown).
        tm.checkServerTrusted(
                new java.security.cert.X509Certificate[]{CeTlsTestCerts.leaf(), CeTlsTestCerts.ca()}, "RSA");
    }

    @Test
    @DisplayName("composite trust still REJECTS a chain trusted by neither the system nor a custom CA")
    void compositeRejectsUntrustedChain() throws Exception {
        var tm = CeCustomTrustStore.buildCompositeTrustManager(List.of()); // no custom CA

        // The self-signed leaf chains to nothing trusted → validation must fail.
        assertThatThrownBy(() -> tm.checkServerTrusted(
                new java.security.cert.X509Certificate[]{CeTlsTestCerts.leaf()}, "RSA"))
                .isInstanceOf(java.security.cert.CertificateException.class);
    }

    // ── whole-chain trust (maximise one-click coverage) ─────────────────────

    @Test
    @DisplayName("parseCertificates reads EVERY block of a multi-cert PEM bundle")
    void parseCertificatesReadsAllBlocks() throws Exception {
        var certs = CeCustomTrustStore.parseCertificates(CeTlsTestCerts.CA_PEM + CeTlsTestCerts.LEAF_PEM);
        assertThat(certs).hasSize(2);
    }

    @Test
    @DisplayName("addTrustedCa imports EVERY certificate in a presented chain (not just the top)")
    void addTrustedCaImportsWholeChain() throws Exception {
        // The proxy chain (root + leaf) is trusted in one call so every host the chain signs
        // is covered, not only the top-of-chain cert.
        CeCustomTrustStore.TrustedCa primary =
                store.addTrustedCa(CeTlsTestCerts.CA_PEM + CeTlsTestCerts.LEAF_PEM);

        assertThat(pemFileCount(caDir)).isEqualTo(2);
        assertThat(store.listTrustedCas()).hasSize(2);
        // The reported "primary" is the self-signed root, not the leaf.
        assertThat(primary.subject()).contains("Test Intercept Root CA");
    }

    // ── Reactor Netty / WebClient coverage (javax.net.ssl.trustStore) ────────

    @Test
    @DisplayName("buildCompositeTrustStore = system anchors PLUS the custom CA (additive, nothing dropped)")
    void compositeTrustStoreIsAdditive() throws Exception {
        int systemOnly = CeCustomTrustStore.buildCompositeTrustStore(List.of()).size();
        KeyStore withCustom = CeCustomTrustStore.buildCompositeTrustStore(List.of(CeTlsTestCerts.ca()));

        assertThat(systemOnly).isGreaterThan(0);                 // JVM default anchors present
        assertThat(withCustom.size()).isEqualTo(systemOnly + 1); // custom CA ADDED, nothing removed
        assertThat(java.util.Collections.list(withCustom.aliases()))
                .anyMatch(a -> a.startsWith("ce-custom-"));       // the custom CA is a trusted entry
    }

    @Test
    @DisplayName("addTrustedCa writes the composite truststore and points javax.net.ssl.trustStore at it")
    void addTrustedCaSetsNettyTrustStoreProperty() throws Exception {
        store.addTrustedCa(CeTlsTestCerts.CA_PEM);

        Path expected = caDir.resolve("ce-composite-truststore.p12");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(System.getProperty("javax.net.ssl.trustStore"))
                .isEqualTo(expected.toAbsolutePath().toString());
        assertThat(System.getProperty("javax.net.ssl.trustStoreType")).isEqualTo("PKCS12");
    }

    @Test
    @DisplayName("the written truststore, loaded via the default TrustManagerFactory (as Netty does), trusts the custom CA")
    void writtenTrustStoreIsTrustedByDefaultTmf() throws Exception {
        store.addTrustedCa(CeTlsTestCerts.CA_PEM);

        // Reactor Netty builds its client trust from the JDK default TrustManagerFactory seeded
        // by javax.net.ssl.trustStore - reproduce exactly that and assert the custom chain validates.
        KeyStore loaded = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(caDir.resolve("ce-composite-truststore.p12"))) {
            loaded.load(in, "changeit".toCharArray());
        }
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(loaded);
        X509TrustManager tm = (X509TrustManager) tmf.getTrustManagers()[0];

        // [leaf, ca] chains to the trusted custom root → accepted (no exception).
        tm.checkServerTrusted(new X509Certificate[]{CeTlsTestCerts.leaf(), CeTlsTestCerts.ca()}, "RSA");
        // and the system anchors are still present (additive, public TLS unbroken).
        assertThat(tm.getAcceptedIssuers().length).isGreaterThan(1);
    }

    @Test
    @DisplayName("Empty dir on init → javax.net.ssl.trustStore is left untouched (no-op)")
    void emptyDirDoesNotSetTrustStoreProperty() {
        String before = System.getProperty("javax.net.ssl.trustStore");

        store.reload();

        assertThat(System.getProperty("javax.net.ssl.trustStore")).isEqualTo(before);
    }

    @Test
    @DisplayName("reload after the composite .p12 is written never re-reads it as a CA")
    void reloadIgnoresGeneratedTrustStoreFile() throws Exception {
        store.addTrustedCa(CeTlsTestCerts.CA_PEM); // writes the .pem AND the composite .p12
        assertThat(Files.exists(caDir.resolve("ce-composite-truststore.p12"))).isTrue();

        store.reload(); // re-scan the dir which now also holds the .p12

        // The .p12 must NOT be parsed as a custom CA - only the one real PEM CA is listed.
        assertThat(store.listTrustedCas())
                .singleElement()
                .satisfies(ca -> assertThat(ca.subject()).contains("Test Intercept Root CA"));
        assertThat(pemFileCount(caDir)).isEqualTo(1);
    }
}
