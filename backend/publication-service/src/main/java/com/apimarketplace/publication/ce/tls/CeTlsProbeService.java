package com.apimarketplace.publication.ce.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * CE-only. Diagnoses whether a TLS-intercepting antivirus / corporate proxy is
 * sitting between this install and the cloud, and - when it is - captures the
 * root CA it presents so the admin can trust it in one click.
 *
 * <p>How: it opens a TLS connection to the cloud host with the JVM's CURRENT
 * (default) trust. If the handshake succeeds, nothing is intercepted (or the CA
 * is already trusted) → {@code intercepted=false}. If it fails with a cert
 * error, it re-handshakes with an inspect-only trust-all manager <em>purely to
 * read</em> the presented chain (it never sends data and never trusts it), and
 * returns the interceptor's root CA for the admin to approve.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
public class CeTlsProbeService {

    private static final Logger log = LoggerFactory.getLogger(CeTlsProbeService.class);
    private static final int HANDSHAKE_TIMEOUT_MS = 8000;

    private final String defaultProbeUrl;

    public CeTlsProbeService(@Value("${cloud-link.keycloak-url:}") String keycloakUrl) {
        this.defaultProbeUrl = keycloakUrl;
    }

    /** Probe the configured cloud host. */
    public ProbeResult probe() {
        return probe(defaultProbeUrl);
    }

    /**
     * Probe an arbitrary https URL/host.
     *
     * @param target an https URL (or bare host); blank falls back to the
     *               configured cloud keycloak URL
     */
    public ProbeResult probe(String target) {
        String url = (target == null || target.isBlank()) ? defaultProbeUrl : target;
        if (url == null || url.isBlank()) {
            return ProbeResult.notConfigured();
        }
        String host;
        int port;
        try {
            HostPort hp = parse(url);
            host = hp.host();
            port = hp.port();
        } catch (Exception e) {
            return ProbeResult.error("invalid_target");
        }

        // 1) Does the CURRENT trust accept the host? Use the JVM-global HTTPS factory
        // (the one CeCustomTrustStore swaps on trust) - NOT SSLSocketFactory.getDefault(),
        // which is cached at first use and would not reflect a just-trusted CA. This keeps
        // a re-probe consistent: once the admin trusts the proxy, the probe stops flagging it.
        if (handshakeSucceeds(host, port, HttpsURLConnection.getDefaultSSLSocketFactory())) {
            return ProbeResult.notIntercepted(host);
        }

        // 2) Handshake failed under current trust → inspect what is being presented.
        X509Certificate[] chain = captureChain(host, port);
        if (chain == null || chain.length == 0) {
            // Failed for a non-certificate reason (DNS, offline, refused): not a trust issue.
            return ProbeResult.unreachable(host);
        }
        X509Certificate root = pickRoot(chain);
        // Did the proxy actually present its self-signed root, or only a re-signed leaf? Many
        // interceptors (e.g. Norton) send ONLY the leaf, so trusting the presented chain covers
        // that one host but NOT every other site re-signed by the same (absent) root. Surface
        // this so the admin knows to also trust the issuing root for full coverage.
        boolean rootPresented = rootPresented(chain);
        if (!rootPresented) {
            log.warn("[CE-TLS] {} is intercepted but the proxy presented only a re-signed leaf "
                    + "(issued by '{}'), not its root CA. Trusting this covers '{}' only; to cover "
                    + "ALL outbound HTTPS, also trust the proxy ROOT '{}' (export it from your "
                    + "antivirus/OS trust store and add it via the trust action).",
                    host, root.getIssuerX500Principal().getName(), host,
                    root.getIssuerX500Principal().getName());
        }
        try {
            // caPem = the ENTIRE presented chain (every block), so trusting it covers the
            // interceptor's root/intermediates/leaf in one click - maximal coverage even when
            // the proxy never sends its self-signed root. The display fields describe the
            // top-of-chain cert (what the admin sees: "issued by <interceptor>").
            return ProbeResult.intercepted(host,
                    root.getSubjectX500Principal().getName(),
                    root.getIssuerX500Principal().getName(),
                    CeCustomTrustStore.sha256(root),
                    toPemBundle(chain),
                    rootPresented);
        } catch (CertificateException e) {
            return ProbeResult.error("encode_failed");
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private boolean handshakeSucceeds(String host, int port, SSLSocketFactory factory) {
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            socket.startHandshake();
            return true;
        } catch (Exception e) {
            log.debug("[CE-TLS] probe handshake to {}:{} failed under current trust: {}",
                    host, port, e.toString());
            return false;
        }
    }

    private X509Certificate[] captureChain(String host, int port) {
        try {
            SSLContext inspect = SSLContext.getInstance("TLS");
            inspect.init(null, new TrustManager[]{INSPECT_ONLY}, new SecureRandom());
            try (SSLSocket socket = (SSLSocket) inspect.getSocketFactory().createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS);
                socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                socket.startHandshake();
                Certificate[] peer = socket.getSession().getPeerCertificates();
                X509Certificate[] x = new X509Certificate[peer.length];
                for (int i = 0; i < peer.length; i++) {
                    x[i] = (X509Certificate) peer[i];
                }
                return x;
            }
        } catch (Exception e) {
            log.debug("[CE-TLS] could not capture chain from {}:{}: {}", host, port, e.toString());
            return null;
        }
    }

    /**
     * The CA to trust = the top of the presented chain: prefer the self-signed
     * root (issuer == subject); else the last (highest) certificate presented.
     */
    static X509Certificate pickRoot(X509Certificate[] chain) {
        for (X509Certificate c : chain) {
            if (c.getSubjectX500Principal().equals(c.getIssuerX500Principal())) {
                return c;
            }
        }
        return chain[chain.length - 1];
    }

    /**
     * True iff the presented chain actually contains the interceptor's self-signed ROOT
     * (some cert with {@code issuer == subject}). When false, the proxy sent only a re-signed
     * leaf/intermediate: trusting the chain fixes the probed host but NOT other sites the same
     * root re-signs, so the admin should additionally trust that root for full coverage.
     */
    static boolean rootPresented(X509Certificate[] chain) {
        for (X509Certificate c : chain) {
            if (c.getSubjectX500Principal().equals(c.getIssuerX500Principal())) {
                return true;
            }
        }
        return false;
    }

    private static String toPem(X509Certificate cert) throws CertificateException {
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    /** Every certificate in the presented chain, concatenated as a PEM bundle. */
    private static String toPemBundle(X509Certificate[] chain) throws CertificateException {
        StringBuilder sb = new StringBuilder();
        for (X509Certificate c : chain) {
            sb.append(toPem(c));
        }
        return sb.toString();
    }

    private static HostPort parse(String target) {
        String t = target.trim();
        if (!t.contains("://")) {
            t = "https://" + t;
        }
        URI uri = URI.create(t);
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("no host in " + target);
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        return new HostPort(host, port);
    }

    private record HostPort(String host, int port) {
    }

    /** Inspect-only trust manager: accepts any chain SO WE CAN READ IT. Never used for real traffic. */
    private static final X509TrustManager INSPECT_ONLY = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /**
     * @param intercepted   true when a cert the current trust rejects is being presented
     * @param reachable     false when the host could not be reached at all (DNS/offline)
     * @param host          the probed host
     * @param caSubject     interceptor root CA subject (only when intercepted)
     * @param caIssuer      interceptor root CA issuer (only when intercepted)
     * @param caSha256      interceptor root CA SHA-256 fingerprint (only when intercepted)
     * @param caPem         interceptor root CA PEM, to POST back to /trust (only when intercepted)
     * @param rootPresented true when the proxy actually presented its self-signed ROOT; false when
     *                      it sent only a re-signed leaf (then trusting it covers this host only -
     *                      the admin should also trust the issuing root, shown in {@code caIssuer})
     * @param error         non-null when the probe itself could not run (config/encoding)
     */
    public record ProbeResult(
            boolean intercepted,
            boolean reachable,
            String host,
            String caSubject,
            String caIssuer,
            String caSha256,
            String caPem,
            boolean rootPresented,
            String error) {

        static ProbeResult notConfigured() {
            return new ProbeResult(false, false, null, null, null, null, null, false, "cloud_not_configured");
        }

        static ProbeResult error(String code) {
            return new ProbeResult(false, false, null, null, null, null, null, false, code);
        }

        static ProbeResult notIntercepted(String host) {
            return new ProbeResult(false, true, host, null, null, null, null, false, null);
        }

        static ProbeResult unreachable(String host) {
            return new ProbeResult(false, false, host, null, null, null, null, false, null);
        }

        static ProbeResult intercepted(String host, String subject, String issuer,
                                       String sha256, String pem, boolean rootPresented) {
            return new ProbeResult(true, true, host, subject, issuer, sha256, pem, rootPresented, null);
        }
    }
}
