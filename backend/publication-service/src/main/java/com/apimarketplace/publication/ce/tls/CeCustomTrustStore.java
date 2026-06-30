package com.apimarketplace.publication.ce.tls;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CE-only. Lets a self-hosted install trust the root CA of a TLS-intercepting
 * antivirus / corporate proxy, so the backend's OUTBOUND HTTPS to the cloud
 * (cloud-link token exchange, LLM relay, catalog bundle, marketplace) is no
 * longer rejected with PKIX {@code unable to find valid certification path}.
 *
 * <p><b>Mechanism.</b> Installs a JVM-global default {@link HttpsURLConnection}
 * SSL socket factory backed by a COMPOSITE trust manager = the JVM's default
 * trust anchors PLUS any custom CA PEM/CRT dropped in {@code ce.trusted-ca-dir}.
 * Every cloud client in CE reaches the network through {@code HttpsURLConnection}
 * (Spring's default {@code SimpleClientHttpRequestFactory} and the relay's raw
 * {@code HttpURLConnection}), so this repairs them ALL with zero client changes.
 *
 * <p><b>Reactor Netty / WebClient.</b> Clients that do NOT use {@code HttpsURLConnection} -
 * notably Reactor Netty (Spring {@code WebClient}, used by the file-download node and other
 * outbound fetches) - build their client SSL from the JDK <em>default</em>
 * {@link TrustManagerFactory}, which reads {@code javax.net.ssl.trustStore}; they ignore the
 * socket factory and {@link SSLContext#setDefault} above. So a TLS-intercepted install could
 * cloud-link yet still hit PKIX on a WebClient download. To cover them too, a COMPOSITE PKCS12
 * truststore (system anchors PLUS the custom CAs) is written and {@code javax.net.ssl.trustStore}
 * is pointed at it - still strictly additive, loaded at startup before the first download.
 *
 * <p><b>Safe.</b> The composite is strictly ADDITIVE - it accepts a chain that
 * the system anchors trust OR that a custom CA trusts; it never drops the system
 * anchors, so there is no trust downgrade. When the dir holds no custom CA the
 * JVM global default is left untouched (pure no-op), which is also why this is
 * inert in cloud (gated to {@code auth.mode=embedded} anyway, and cloud never
 * has a custom CA dir). New trust is added only by an explicit admin action via
 * {@link #addTrustedCa(String)} (see the {@code /api/ce/tls/trust} endpoint).
 */
@Component
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
public class CeCustomTrustStore {

    private static final Logger log = LoggerFactory.getLogger(CeCustomTrustStore.class);

    /** File name of the generated composite truststore inside {@code ce.trusted-ca-dir}. */
    static final String COMPOSITE_TRUSTSTORE_FILE = "ce-composite-truststore.p12";
    /** Password for the generated composite truststore (matches the JDK cacerts default). */
    static final String COMPOSITE_TRUSTSTORE_PASSWORD = "changeit";

    private final Path trustedCaDir;

    /** Current installed custom CAs (immutable snapshot, swapped on reload). */
    private final AtomicReference<List<X509Certificate>> customCas =
            new AtomicReference<>(List.of());

    public CeCustomTrustStore(
            @Value("${ce.trusted-ca-dir:/app/data/keys/trusted-cas}") String trustedCaDir) {
        this.trustedCaDir = Path.of(trustedCaDir);
    }

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            // Never block startup on a bad cert dir - the install simply keeps
            // the default trust (cloud features may fail until the admin trusts
            // their proxy CA, exactly the pre-feature behaviour).
            log.warn("[CE-TLS] Failed to initialise custom trust store from {}: {}",
                    trustedCaDir, e.getMessage());
        }
    }

    /**
     * Re-read the trusted-CA directory and (re)install the JVM-global socket
     * factory. Called at startup and after a new CA is trusted, so the effect
     * is immediate without a restart.
     */
    public synchronized void reload() {
        List<X509Certificate> loaded = loadCustomCas();
        customCas.set(List.copyOf(loaded));
        if (loaded.isEmpty()) {
            log.info("[CE-TLS] No custom CA in {} - using default trust only.", trustedCaDir);
            return; // leave the JVM default untouched (no-op)
        }
        installGlobalSocketFactory(loaded);
        log.info("[CE-TLS] Installed {} custom CA(s) into the global trust: {}",
                loaded.size(), loaded.stream().map(c -> c.getSubjectX500Principal().getName()).toList());
    }

    /**
     * Trust a CA bundle (one OR MORE PEM/DER certificates - typically the full chain an
     * intercepting proxy presents). Persists EACH certificate to the trusted-CA dir and
     * reloads the global trust so outbound HTTPS works immediately. Idempotent per
     * certificate (same SHA-256 → not duplicated). Trusting the whole chain - not just the
     * root - maximises one-click coverage: when an interceptor presents only a re-signed
     * leaf/intermediate and never sends its self-signed root, trusting every presented cert
     * still covers the hosts those certs sign.
     *
     * @return the identity of the most significant certificate (the self-signed root if the
     *         bundle contains one, otherwise the first) - for display/logging
     * @throws CertificateException if no valid X.509 certificate is found
     * @throws IOException          if the dir/file cannot be written
     */
    public synchronized TrustedCa addTrustedCa(String certPem) throws CertificateException, IOException {
        List<X509Certificate> certs = parseCertificates(certPem);
        if (certs.isEmpty()) {
            throw new CertificateException("No X.509 certificate found in the supplied PEM");
        }
        Files.createDirectories(trustedCaDir);
        for (X509Certificate cert : certs) {
            Path target = trustedCaDir.resolve(sha256(cert) + ".pem");
            if (!Files.exists(target)) {
                Files.writeString(target, toPem(cert), StandardCharsets.US_ASCII);
            }
        }
        reload();
        X509Certificate primary = primaryOf(certs);
        return new TrustedCa(primary.getSubjectX500Principal().getName(),
                primary.getIssuerX500Principal().getName(), sha256(primary));
    }

    /** The self-signed root if the bundle contains one, else the first certificate. */
    private static X509Certificate primaryOf(List<X509Certificate> certs) {
        for (X509Certificate c : certs) {
            if (c.getSubjectX500Principal().equals(c.getIssuerX500Principal())) {
                return c;
            }
        }
        return certs.get(0);
    }

    /** Identities of the currently trusted custom CAs (for status/UI). */
    public List<TrustedCa> listTrustedCas() {
        List<TrustedCa> out = new ArrayList<>();
        for (X509Certificate c : customCas.get()) {
            out.add(new TrustedCa(c.getSubjectX500Principal().getName(),
                    c.getIssuerX500Principal().getName(), sha256Quiet(c)));
        }
        return out;
    }

    // ── internals ──────────────────────────────────────────────────────────

    private List<X509Certificate> loadCustomCas() {
        List<X509Certificate> out = new ArrayList<>();
        if (!Files.isDirectory(trustedCaDir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(trustedCaDir, "*.{pem,crt,cer}")) {
            for (Path p : ds) {
                try {
                    out.add(parseCertificate(Files.readString(p, StandardCharsets.US_ASCII)));
                } catch (Exception e) {
                    log.warn("[CE-TLS] Skipping unreadable CA file {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[CE-TLS] Could not list {}: {}", trustedCaDir, e.getMessage());
        }
        return out;
    }

    private void installGlobalSocketFactory(List<X509Certificate> extraCas) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{buildCompositeTrustManager(extraCas)}, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            SSLContext.setDefault(ctx);
            // Cover Reactor Netty / WebClient (file-download node + other outbound fetches),
            // which ignore the socket factory above and read javax.net.ssl.trustStore instead.
            installNettyTrustStoreProperty(extraCas);
        } catch (Exception e) {
            log.error("[CE-TLS] Failed to install custom trust - cloud HTTPS may keep failing", e);
        }
    }

    /**
     * Write a COMPOSITE PKCS12 truststore (system trust anchors PLUS the custom CAs) and point
     * {@code javax.net.ssl.trustStore} at it, so clients that build their SSL from the JDK
     * default {@link TrustManagerFactory} - Reactor Netty / {@code WebClient} above all - also
     * trust the intercepting proxy CA. Strictly additive (the system anchors are always
     * included), so public TLS is never narrowed. Best-effort: a failure here only means the
     * Netty path keeps failing PKIX, exactly the pre-fix behaviour, and never breaks startup.
     *
     * <p><b>Timing.</b> The property is authoritative at STARTUP - the realistic case (a CA was
     * persisted to disk earlier, the app is then restarted, {@code @PostConstruct reload()} sets
     * the property before the first WebClient download fires at workflow runtime). Reactor Netty
     * caches its default client {@code SslProvider} on its first HTTPS use and does NOT rebuild it
     * when this property later changes; so a runtime {@code /trust} action AFTER a WebClient HTTPS
     * call has already run in this JVM takes effect for the Netty path only on the next restart
     * (the {@code HttpsURLConnection} path above refreshes immediately). Persist-then-restart is
     * the supported path for full coverage.
     */
    private void installNettyTrustStoreProperty(List<X509Certificate> extraCas) {
        try {
            Path storePath = trustedCaDir.resolve(COMPOSITE_TRUSTSTORE_FILE);
            Files.createDirectories(trustedCaDir);
            KeyStore composite = buildCompositeTrustStore(extraCas);
            try (OutputStream os = Files.newOutputStream(storePath)) {
                composite.store(os, COMPOSITE_TRUSTSTORE_PASSWORD.toCharArray());
            }
            System.setProperty("javax.net.ssl.trustStore", storePath.toAbsolutePath().toString());
            System.setProperty("javax.net.ssl.trustStorePassword", COMPOSITE_TRUSTSTORE_PASSWORD);
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            log.info("[CE-TLS] Composite truststore written ({} entries) and javax.net.ssl.trustStore set - "
                    + "WebClient/Netty outbound HTTPS now trusts the custom CA(s): {}",
                    composite.size(), storePath);
        } catch (Exception e) {
            log.error("[CE-TLS] Failed to install composite truststore property - "
                    + "Netty/WebClient HTTPS may keep failing PKIX", e);
        }
    }

    /**
     * Build the composite truststore = ALL JVM default trust anchors PLUS the given custom CAs.
     * Package-private so the additive content can be unit-tested. Strictly additive: the system
     * anchors are always included, so this never narrows public TLS trust.
     */
    static KeyStore buildCompositeTrustStore(List<X509Certificate> extraCas) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        int i = 0;
        for (X509Certificate anchor : defaultTrustManager().getAcceptedIssuers()) {
            ks.setCertificateEntry("system-" + (i++), anchor);
        }
        int j = 0;
        for (X509Certificate ca : extraCas) {
            ks.setCertificateEntry("ce-custom-" + (j++), ca);
        }
        return ks;
    }

    /**
     * The trust manager installed globally: the system anchors PLUS the given custom CAs.
     * Strictly additive - a chain is trusted if EITHER side accepts it; the system anchors
     * are never removed. Package-private so the additive-trust guarantee can be unit-tested.
     */
    static X509TrustManager buildCompositeTrustManager(List<X509Certificate> extraCas) throws Exception {
        return new CompositeTrustManager(defaultTrustManager(), trustManagerFor(extraCas));
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return firstX509(tmf);
    }

    private static X509TrustManager trustManagerFor(List<X509Certificate> cas) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (X509Certificate c : cas) {
            ks.setCertificateEntry("ce-custom-ca-" + (i++), c);
        }
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return firstX509(tmf);
    }

    private static X509TrustManager firstX509(TrustManagerFactory tmf) {
        return Arrays.stream(tmf.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No X509TrustManager available"));
    }

    static X509Certificate parseCertificate(String pemOrDer) throws CertificateException {
        byte[] bytes = pemOrDer.getBytes(StandardCharsets.US_ASCII);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(bytes));
    }

    /** Parse EVERY X.509 certificate in a PEM bundle (one or more concatenated blocks). */
    static List<X509Certificate> parseCertificates(String pemOrDer) throws CertificateException {
        byte[] bytes = pemOrDer.getBytes(StandardCharsets.US_ASCII);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<>();
        for (java.security.cert.Certificate c : cf.generateCertificates(new ByteArrayInputStream(bytes))) {
            if (c instanceof X509Certificate x) {
                out.add(x);
            }
        }
        return out;
    }

    static String sha256(X509Certificate cert) throws CertificateException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(cert.getEncoded()));
        } catch (Exception e) {
            throw new CertificateException("Cannot fingerprint certificate", e);
        }
    }

    private static String sha256Quiet(X509Certificate cert) {
        try {
            return sha256(cert);
        } catch (CertificateException e) {
            return "";
        }
    }

    private static String toPem(X509Certificate cert) throws CertificateException {
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    /** Subject/issuer/SHA-256 of a trusted CA - what the UI shows the admin. */
    public record TrustedCa(String subject, String issuer, String sha256) {
    }

    /**
     * Trusts a chain if EITHER the system anchors OR the custom CAs accept it.
     * Additive only - never narrows the default trust.
     */
    private static final class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager custom;

        CompositeTrustManager(X509TrustManager system, X509TrustManager custom) {
            this.system = system;
            this.custom = custom;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException systemFailure) {
                try {
                    custom.checkServerTrusted(chain, authType);
                } catch (Exception customFailure) {
                    // Any custom-side failure (incl. an empty custom anchor set, which the
                    // PKIX validator reports as a RuntimeException) means the custom CAs did
                    // not accept the chain → surface the canonical system trust error.
                    throw systemFailure;
                }
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = system.getAcceptedIssuers();
            X509Certificate[] b = custom.getAcceptedIssuers();
            X509Certificate[] all = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            return all;
        }
    }
}
