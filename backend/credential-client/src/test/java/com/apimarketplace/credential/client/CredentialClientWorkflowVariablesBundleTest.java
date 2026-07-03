package com.apimarketplace.credential.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialClient#getWorkflowVariablesBundle(String, String)} -
 * transport-level behavior against a real HTTP server (the client builds its
 * own RestTemplate, so the wire is the only honest seam).
 *
 * <p>Two contracts matter for the orchestrator's per-run fetch:
 * <ol>
 *   <li><b>Explicit scope headers.</b> Runs execute asynchronously with no
 *       inbound request context, so {@code X-User-ID} and
 *       {@code X-Organization-ID} must be set explicitly on the outbound call
 *       (no OrgContextHeaderForwarder reliance).</li>
 *   <li><b>Best-effort.</b> Any failure (unreachable, 5xx, malformed body)
 *       degrades to an EMPTY map - a run without variables, never a run that
 *       fails because auth-service hiccuped.</li>
 * </ol>
 */
@DisplayName("CredentialClient.getWorkflowVariablesBundle - wire behavior")
class CredentialClientWorkflowVariablesBundleTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(
            "{\"variables\":{\"api_url\":\"https://x\",\"retries\":3,\"debug\":true,"
                    + "\"cfg\":{\"host\":\"db\"}},\"count\":4}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private final AtomicReference<String> lastUserId = new AtomicReference<>();
    private final AtomicReference<Boolean> lastHadOrgHeader = new AtomicReference<>();
    private final AtomicReference<String> lastOrgId = new AtomicReference<>();
    private final AtomicReference<String> lastGatewaySecret = new AtomicReference<>();

    private CredentialClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastUri.set(exchange.getRequestURI().toString());
            lastUserId.set(exchange.getRequestHeaders().getFirst("X-User-ID"));
            lastHadOrgHeader.set(exchange.getRequestHeaders().containsKey("X-Organization-ID"));
            lastOrgId.set(exchange.getRequestHeaders().getFirst("X-Organization-ID"));
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
    @DisplayName("returns the 'variables' map with Jackson-typed values and hits the documented endpoint")
    void returnsVariablesMapFromEnvelope() {
        Map<String, Object> bundle = client.getWorkflowVariablesBundle("tenant-9", null);

        assertThat(bundle)
                .containsEntry("api_url", "https://x")
                .containsEntry("retries", 3)
                .containsEntry("debug", true)
                .containsEntry("cfg", Map.of("host", "db"));
        assertThat(lastUri.get())
                .startsWith("/api/internal/variables/bundle")
                .contains("tenantId=tenant-9");
    }

    @Test
    @DisplayName("sets X-User-ID explicitly on the outbound request")
    void setsUserIdHeaderExplicitly() {
        client.getWorkflowVariablesBundle("tenant-9", null);

        assertThat(lastUserId.get()).isEqualTo("tenant-9");
    }

    @Test
    @DisplayName("sets X-Organization-ID explicitly when an org scope is given - async runs have nothing to forward")
    void setsOrgHeaderExplicitly() {
        client.getWorkflowVariablesBundle("tenant-9", "org-4");

        assertThat(lastOrgId.get()).isEqualTo("org-4");
    }

    @Test
    @DisplayName("omits X-Organization-ID entirely for the personal scope (null org)")
    void omitsOrgHeaderForPersonalScope() {
        client.getWorkflowVariablesBundle("tenant-9", null);

        assertThat(lastHadOrgHeader.get()).isFalse();
    }

    @Test
    @DisplayName("omits X-Organization-ID for a blank org id - blank must not become a bogus workspace")
    void omitsOrgHeaderForBlankOrg() {
        client.getWorkflowVariablesBundle("tenant-9", "   ");

        assertThat(lastHadOrgHeader.get()).isFalse();
    }

    @Test
    @DisplayName("null or blank tenantId short-circuits to an empty map without any HTTP call")
    void blankTenantShortCircuits() {
        assertThat(client.getWorkflowVariablesBundle(null, "org-4")).isEmpty();
        assertThat(client.getWorkflowVariablesBundle("  ", "org-4")).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("degrades to an empty map on a 5xx from auth-service (best-effort, run continues)")
    void emptyMapOnServerError() {
        status.set(500);
        body.set("boom");

        assertThat(client.getWorkflowVariablesBundle("tenant-9", null)).isEmpty();
    }

    @Test
    @DisplayName("degrades to an empty map when auth-service is unreachable")
    void emptyMapOnTransportFailure() {
        server.stop(0);

        assertThat(client.getWorkflowVariablesBundle("tenant-9", null)).isEmpty();
    }

    @Test
    @DisplayName("degrades to an empty map when the body has no 'variables' key")
    void emptyMapWhenVariablesKeyMissing() {
        body.set("{\"count\":0}");

        assertThat(client.getWorkflowVariablesBundle("tenant-9", null)).isEmpty();
    }

    @Test
    @DisplayName("degrades to an empty map when 'variables' is not an object")
    void emptyMapWhenVariablesNotAMap() {
        body.set("{\"variables\":[\"not\",\"a\",\"map\"],\"count\":3}");

        assertThat(client.getWorkflowVariablesBundle("tenant-9", null)).isEmpty();
    }

    @Test
    @DisplayName("an empty variables object round-trips as an empty map (no variables defined is not an error)")
    void emptyVariablesObjectRoundTrips() {
        body.set("{\"variables\":{},\"count\":0}");

        assertThat(client.getWorkflowVariablesBundle("tenant-9", null)).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("signs the request with the gateway HMAC headers when a gateway secret is configured")
    void signsWithGatewaySecretWhenConfigured() {
        CredentialClient signedClient = new CredentialClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-shared-gateway-secret");

        Map<String, Object> bundle = signedClient.getWorkflowVariablesBundle("tenant-9", "org-4");

        assertThat(bundle).isNotEmpty();
        assertThat(lastGatewaySecret.get()).startsWith("gw_");
        assertThat(lastOrgId.get()).isEqualTo("org-4");
    }
}
