package com.apimarketplace.credential.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialClient#getCredentialStateVersion(String)} - transport-level
 * behavior against a real HTTP server (the client builds its own RestTemplate,
 * so the wire is the only honest seam).
 *
 * <p>The fail-open paths are the design's safety valve for the 2026-06-11
 * "set as default ignored by the chat agent" fix: when auth-service is
 * unreachable or answers garbage, the caller must receive the STABLE
 * {@link CredentialClient#STATE_VERSION_UNAVAILABLE} marker so catalog's
 * response cache keeps functioning instead of throwing or key-thrashing.
 */
@DisplayName("CredentialClient.getCredentialStateVersion - wire behavior")
class CredentialClientStateVersionTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{\"version\":\"3:1700000000000\"}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private final AtomicReference<String> lastUserId = new AtomicReference<>();
    private final AtomicReference<String> lastProviderId = new AtomicReference<>();
    private final AtomicReference<String> lastGatewayTimestamp = new AtomicReference<>();
    private final AtomicReference<String> lastGatewaySecret = new AtomicReference<>();

    private CredentialClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastUri.set(exchange.getRequestURI().toString());
            lastUserId.set(exchange.getRequestHeaders().getFirst("X-User-ID"));
            lastProviderId.set(exchange.getRequestHeaders().getFirst("X-Provider-ID"));
            lastGatewayTimestamp.set(exchange.getRequestHeaders().getFirst("X-Gateway-Timestamp"));
            lastGatewaySecret.set(exchange.getRequestHeaders().getFirst("X-Gateway-Secret"));
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        client = new CredentialClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("returns the version verbatim and hits the documented endpoint with the userId")
    void returnsVersionVerbatim() {
        String version = client.getCredentialStateVersion("tenant-5");

        assertThat(version).isEqualTo("3:1700000000000");
        assertThat(lastUserId.get()).isEqualTo("tenant-5");
        assertThat(lastUri.get())
                .startsWith("/api/internal/credentials/state-version")
                .contains("userId=tenant-5");
    }

    @Test
    @DisplayName("adds gateway HMAC headers when configured")
    void addsGatewayHmacHeadersWhenConfigured() {
        CredentialClient signedClient = new CredentialClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-shared-gateway-secret");

        String version = signedClient.getCredentialStateVersion("tenant-5");

        assertThat(version).isEqualTo("3:1700000000000");
        assertThat(lastProviderId.get()).isEqualTo("internal-credential-client");
        assertThat(lastGatewayTimestamp.get()).matches("\\d+");
        assertThat(lastGatewaySecret.get()).startsWith("gw_");
    }

    @Test
    @DisplayName("fails open to the stable 'na' marker when the body has no usable version")
    void failsOpenOnMissingOrBlankVersion() {
        body.set("{}");
        assertThat(client.getCredentialStateVersion("tenant-5"))
                .isEqualTo(CredentialClient.STATE_VERSION_UNAVAILABLE);

        body.set("{\"version\":\"  \"}");
        assertThat(client.getCredentialStateVersion("tenant-5"))
                .isEqualTo(CredentialClient.STATE_VERSION_UNAVAILABLE);

        body.set("{\"version\":42}");
        assertThat(client.getCredentialStateVersion("tenant-5"))
                .isEqualTo(CredentialClient.STATE_VERSION_UNAVAILABLE);
    }

    @Test
    @DisplayName("fails open on a 5xx from auth-service")
    void failsOpenOnServerError() {
        status.set(500);
        body.set("boom");

        assertThat(client.getCredentialStateVersion("tenant-5"))
                .isEqualTo(CredentialClient.STATE_VERSION_UNAVAILABLE);
    }

    @Test
    @DisplayName("fails open on transport failure (auth-service unreachable)")
    void failsOpenOnTransportFailure() {
        server.stop(0);

        assertThat(client.getCredentialStateVersion("tenant-5"))
                .isEqualTo(CredentialClient.STATE_VERSION_UNAVAILABLE);
    }

    @Test
    @DisplayName("blank userId short-circuits to 'na' without any HTTP call")
    void blankUserIdShortCircuits() {
        assertThat(client.getCredentialStateVersion("  ")).isEqualTo(CredentialClient.STATE_VERSION_UNAVAILABLE);
        assertThat(client.getCredentialStateVersion(null)).isEqualTo(CredentialClient.STATE_VERSION_UNAVAILABLE);
        assertThat(requests.get()).isZero();
    }
}
