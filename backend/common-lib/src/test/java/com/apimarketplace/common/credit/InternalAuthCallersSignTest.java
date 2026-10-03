package com.apimarketplace.common.credit;

import com.apimarketplace.common.web.GatewaySignatureV2;
import com.apimarketplace.common.web.GatewaySignatureVerifier;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA: auth-service can require the gateway HMAC on its whole {@code /api/internal/auth/} prefix
 * ({@code AUTH_INTERNAL_HMAC_REQUIRED_PATH}). Two common-lib clients called it unsigned: the
 * pricing snapshot every budget guard reads, and the credit dead-letter forward. Arming the gate
 * would have refused both, the second silently (fire-and-forget), losing failed debits.
 *
 * <p>Both run against a local HTTP server, so what is checked is the request as auth-service
 * receives it, with the verifiers its filter uses.
 */
@DisplayName("common-lib callers of /api/internal/auth/ sign their requests")
class InternalAuthCallersSignTest {

    private static final String SECRET = "internal-auth-callers-test-secret-0123456789";

    private HttpServer server;
    private final Map<String, Headers> received = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/auth/", exchange -> {
            received.put(exchange.getRequestURI().getPath(), exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"rates\":[{\"provider\":\"openai\",\"model\":\"gpt-5\","
                    + "\"inputRate\":1.0,\"outputRate\":4.0,\"fixedCost\":0}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void assertSigned(Headers headers, String providerId, String method, String path) {
        String timestamp = headers.getFirst("X-Gateway-Timestamp");
        assertThat(headers.getFirst("X-Provider-ID")).isEqualTo(providerId);
        assertThat(new GatewaySignatureVerifier(SECRET).isValid(headers.getFirst("X-Gateway-Secret"),
                providerId, timestamp, headers.getFirst("X-User-ID"), headers.getFirst("X-Organization-ID")))
                .as("v1 signature bound to the identity headers actually sent")
                .isTrue();
        assertThat(GatewaySignatureV2.verify(SECRET, method, path, null,
                name -> {
                    List<String> values = headers.get(name);
                    return values == null ? List.of() : values;
                },
                headers.getFirst(GatewaySignatureV2.HEADER), 60_000, System.currentTimeMillis()))
                .as("v2 signature over the method and path actually sent")
                .isTrue();
    }

    @Test
    @DisplayName("the pricing snapshot read is signed (v1 + v2) and still parsed")
    void pricingSnapshotIsSigned() {
        PricingSnapshotClient client = new PricingSnapshotClient(baseUrl(), SECRET);

        client.refresh();

        assertSigned(received.get("/api/internal/auth/pricing/snapshot"),
                PricingSnapshotClient.INTERNAL_PROVIDER_ID, "GET", "/api/internal/auth/pricing/snapshot");
        assertThat(client.isHealthy()).isTrue();
        assertThat(client.getRates("openai", "gpt-5")).isPresent();
    }

    @Test
    @DisplayName("the pricing snapshot read stays unsigned without a secret (dev and CE)")
    void pricingSnapshotWithoutSecretIsUnsigned() {
        new PricingSnapshotClient(baseUrl()).refresh();

        Headers headers = received.get("/api/internal/auth/pricing/snapshot");
        assertThat(headers.getFirst("X-Gateway-Secret")).isNull();
        assertThat(headers.getFirst(GatewaySignatureV2.HEADER)).isNull();
    }

    @Test
    @DisplayName("the credit dead-letter forward is signed, bound to the organization header it carries")
    void deadLetterForwardIsSigned() {
        HttpCreditDeadLetterHandler handler =
                new HttpCreditDeadLetterHandler(new RestTemplate(), baseUrl(), SECRET);

        handler.persistFailedConsumption("42", "AGENT_EXECUTION", "exec-1", "openai", "gpt-5",
                100, 50, "Connection refused", "org-1");

        Headers headers = received.get("/api/internal/auth/credit/dead-letter");
        assertThat(headers.getFirst("X-Organization-ID")).isEqualTo("org-1");
        assertSigned(headers, HttpCreditDeadLetterHandler.INTERNAL_PROVIDER_ID,
                "POST", "/api/internal/auth/credit/dead-letter");
    }
}
