package com.apimarketplace.auth.client;

import com.apimarketplace.common.web.GatewaySignatureV2;
import com.apimarketplace.common.web.GatewaySignatureVerifier;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA: auth-service can require the gateway HMAC on its whole {@code /api/internal/auth/}
 * prefix, and {@link AuthClient} is the main caller of that prefix, so it must sign every call.
 * Before this change it signed nothing, and arming the gate would have refused every plan-limit,
 * role, org-restriction, purge, lifecycle and notification-mail call in the cluster.
 *
 * <p>Runs the client's real RestTemplates against a local HTTP server, so the v2 signature that
 * the interceptor adds at send time is on the wire exactly as auth-service receives it, and checks
 * both signatures with the verifiers auth-service's filter uses.
 */
@DisplayName("AuthClient signs every call to auth-service's internal API")
class AuthClientSignedRequestsTest {

    private static final String SECRET = "auth-client-signing-test-secret-0123456789";

    private HttpServer server;
    private final Map<String, Headers> received = new ConcurrentHashMap<>();
    private final Map<String, String> receivedQuery = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/auth/", exchange -> {
            URI uri = exchange.getRequestURI();
            received.put(uri.getPath(), exchange.getRequestHeaders());
            if (uri.getRawQuery() != null) {
                receivedQuery.put(uri.getPath(), uri.getRawQuery());
            }
            byte[] body = "{\"planCode\":\"PRO\",\"limit\":5,\"requirements\":{}}".getBytes(StandardCharsets.UTF_8);
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

    private static void assertSigned(Headers headers, String method, String path, String query, String userId) {
        String timestamp = headers.getFirst("X-Gateway-Timestamp");
        assertThat(headers.getFirst("X-Provider-ID")).isEqualTo(AuthClient.INTERNAL_PROVIDER_ID);
        assertThat(new GatewaySignatureVerifier(SECRET).isValid(headers.getFirst("X-Gateway-Secret"),
                AuthClient.INTERNAL_PROVIDER_ID, timestamp, userId, null))
                .as("v1 signature bound to the X-User-ID the request carries")
                .isTrue();
        assertThat(GatewaySignatureV2.verify(SECRET, method, path, query,
                name -> {
                    List<String> values = headers.get(name);
                    return values == null ? List.of() : values;
                },
                headers.getFirst(GatewaySignatureV2.HEADER), 60_000, System.currentTimeMillis()))
                .as("v2 signature over the method, path and query actually sent")
                .isTrue();
    }

    @Test
    @DisplayName("a call on the main template carries valid v1 and v2 signatures")
    void mainTemplateCallIsSigned() {
        AuthClient client = new AuthClient(baseUrl(), SECRET);

        assertThat(client.getResourceLimit("42", "WORKFLOW")).isNotNull();

        String path = "/api/internal/auth/plans/limits/WORKFLOW";
        assertSigned(received.get(path), "GET", path, null, "42");
    }

    @Test
    @DisplayName("a call on the bounded template (plan features) is signed too")
    void boundedTemplateCallIsSigned() {
        AuthClient client = new AuthClient(baseUrl(), SECRET);

        client.getPlanFeatures("42");

        String path = "/api/internal/auth/plan-features";
        assertSigned(received.get(path), "GET", path, null, "42");
    }

    @Test
    @DisplayName("a call with a query string and no user (purges feed) binds the query in v2")
    void queryAndNoUserAreSigned() {
        AuthClient client = new AuthClient(baseUrl(), SECRET);

        client.getPurges(7L, 50);

        String path = "/api/internal/auth/purges";
        assertSigned(received.get(path), "GET", path, receivedQuery.get(path), null);
    }

    @Test
    @DisplayName("without a secret the client stays unsigned (dev and CE, where nothing verifies it)")
    void noSecretSendsUnsigned() {
        AuthClient client = new AuthClient(baseUrl());

        client.getResourceLimit("42", "WORKFLOW");

        Headers headers = received.get("/api/internal/auth/plans/limits/WORKFLOW");
        assertThat(headers.getFirst("X-Gateway-Secret")).isNull();
        assertThat(headers.getFirst(GatewaySignatureV2.HEADER)).isNull();
    }
}
