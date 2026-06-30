package com.apimarketplace.publication.ce.tls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CeTlsProbeService")
class CeTlsProbeServiceTest {

    @Test
    @DisplayName("pickRoot returns the self-signed CA when present in the chain")
    void pickRootReturnsSelfSignedCa() throws Exception {
        X509Certificate ca = CeTlsTestCerts.ca();
        X509Certificate leaf = CeTlsTestCerts.leaf();

        // Order should not matter - the self-signed root is what the admin must trust.
        assertThat(CeTlsProbeService.pickRoot(new X509Certificate[]{leaf, ca})).isSameAs(ca);
        assertThat(CeTlsProbeService.pickRoot(new X509Certificate[]{ca, leaf})).isSameAs(ca);
    }

    @Test
    @DisplayName("pickRoot falls back to the highest (last) cert when no self-signed root is presented")
    void pickRootFallsBackToLast() throws Exception {
        X509Certificate leaf = CeTlsTestCerts.leaf();

        // A chain with only a non-self-signed leaf (issuer != subject) → return it as the best candidate.
        assertThat(CeTlsProbeService.pickRoot(new X509Certificate[]{leaf})).isSameAs(leaf);
    }

    @Test
    @DisplayName("rootPresented is true when the chain contains the self-signed root")
    void rootPresentedTrueWhenRootInChain() throws Exception {
        X509Certificate ca = CeTlsTestCerts.ca();
        X509Certificate leaf = CeTlsTestCerts.leaf();

        assertThat(CeTlsProbeService.rootPresented(new X509Certificate[]{leaf, ca})).isTrue();
        assertThat(CeTlsProbeService.rootPresented(new X509Certificate[]{ca})).isTrue();
    }

    @Test
    @DisplayName("rootPresented is false when the proxy presents only a re-signed leaf (the Norton case)")
    void rootPresentedFalseWhenOnlyLeaf() throws Exception {
        X509Certificate leaf = CeTlsTestCerts.leaf();

        // Norton sends ONLY the re-signed leaf, never its self-signed root → trusting it covers
        // this host only, so the probe must flag that the issuing root still needs trusting.
        assertThat(CeTlsProbeService.rootPresented(new X509Certificate[]{leaf})).isFalse();
    }

    @Test
    @DisplayName("probe reports cloud_not_configured when no cloud URL is configured")
    void probeNotConfigured() {
        CeTlsProbeService svc = new CeTlsProbeService("");

        CeTlsProbeService.ProbeResult result = svc.probe("");

        assertThat(result.intercepted()).isFalse();
        assertThat(result.error()).isEqualTo("cloud_not_configured");
    }

    @Test
    @DisplayName("probe reports invalid_target for an unparseable host")
    void probeInvalidTarget() {
        CeTlsProbeService svc = new CeTlsProbeService("https://livecontext.ai");

        CeTlsProbeService.ProbeResult result = svc.probe("has spaces in it");

        assertThat(result.error()).isEqualTo("invalid_target");
    }

    @Test
    @DisplayName("probe detects interception and captures the proxy root CA when the default trust rejects the host")
    void probeDetectsInterceptionAndCapturesRootCa() throws Exception {
        // A throwaway TLS server presenting [leaf, Test Intercept Root CA] stands in for an
        // intercepting proxy. The default JVM trust rejects it (the CA is not a system anchor),
        // so the probe must fall back to inspection and surface the self-signed root to trust.
        SSLContext serverCtx = CeTlsTestCerts.serverSslContext();
        try (SSLServerSocket server =
                     (SSLServerSocket) serverCtx.getServerSocketFactory().createServerSocket(0)) {
            int port = server.getLocalPort();
            // Accept each connection and hand the handshake to its own thread, so the probe's
            // TWO connections (step-1 validating, then step-2 inspect) are both serviced
            // promptly - a single-threaded accept loop would stall step-2 behind step-1's
            // aborted handshake.
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    final SSLSocket s;
                    try {
                        s = (SSLSocket) server.accept();
                    } catch (Exception e) {
                        return; // server closed
                    }
                    Thread handler = new Thread(() -> {
                        try {
                            s.setSoTimeout(4000);
                            s.startHandshake();
                        } catch (Exception ignored) {
                            // step-1 client aborts after rejecting the untrusted cert - expected.
                        } finally {
                            try { s.close(); } catch (Exception ignored) { /* noop */ }
                        }
                    });
                    handler.setDaemon(true);
                    handler.start();
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            CeTlsProbeService svc = new CeTlsProbeService("https://localhost:" + port);
            CeTlsProbeService.ProbeResult result = svc.probe();

            // The default JVM trust rejects the server's CA, so the probe must fall back to
            // inspection and surface a root CA to trust. We assert the BEHAVIOUR (interception
            // detected + a CA captured) rather than a specific subject: on a dev machine whose
            // antivirus itself intercepts localhost TLS, the captured CA is the AV's, not our
            // test root - which is exactly the real-world case this feature handles.
            assertThat(result.intercepted()).isTrue();
            assertThat(result.reachable()).isTrue();
            assertThat(result.caPem()).contains("BEGIN CERTIFICATE");
            assertThat(result.caSubject()).isNotBlank();
        }
    }
}
