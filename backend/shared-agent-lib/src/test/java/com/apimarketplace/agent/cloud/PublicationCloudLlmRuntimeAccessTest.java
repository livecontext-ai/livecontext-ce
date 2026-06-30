package com.apimarketplace.agent.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PublicationCloudLlmRuntimeAccess")
class PublicationCloudLlmRuntimeAccessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private PublicationCloudLlmRuntimeAccess access;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        access = new PublicationCloudLlmRuntimeAccess(
                new RestTemplate(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("resolves source without requesting cloud relay credentials")
    void resolvesSourceWithoutRelayCredentials() {
        server.createContext("/api/internal/cloud-link/source/42", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-User-ID")).isEqualTo("42");
            writeJson(exchange, 200, Map.of("source", "CLOUD"));
        });

        assertThat(access.getEffectiveLlmSource("42")).isEqualTo(CloudLlmSource.CLOUD);
    }

    @Test
    @DisplayName("fail-closes when the CE LLM source cannot be resolved")
    void failClosesWhenSourceCannotBeResolved() {
        server.createContext("/api/internal/cloud-link/source/42",
                exchange -> writeText(exchange, 503, "publication-service unavailable"));

        assertThatThrownBy(() -> access.getEffectiveLlmSource("42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to resolve CE LLM source");
    }

    @Test
    @DisplayName("resolves cloud credentials only when runtime is ready")
    void resolvesCredentialsOnlyWhenRuntimeReady() {
        server.createContext("/api/internal/cloud-link/runtime/42", exchange -> writeJson(exchange, 200, Map.of(
                "source", "CLOUD",
                "cloudReady", true,
                "accessToken", "access-token",
                "installId", "install-1",
                "cloudApiUrl", "https://livecontext.ai/api"
        )));

        assertThat(access.resolveCloudRuntime("42"))
                .hasValue(new CloudLlmRuntimeCredentials(
                        "access-token",
                        "install-1",
                        "https://livecontext.ai/api"));
    }

    @Test
    @DisplayName("fail-closes when cloud runtime lookup fails")
    void failClosesWhenRuntimeCannotBeResolved() {
        server.createContext("/api/internal/cloud-link/runtime/42",
                exchange -> writeText(exchange, 503, "publication-service unavailable"));

        assertThatThrownBy(() -> access.resolveCloudRuntime("42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to resolve CE LLM source");
    }

    @Test
    @DisplayName("resolveActiveCloudRuntime: returns the install's active-link creds when ready - even on a BYOK link")
    void resolvesActiveInstallRuntime() {
        // source=BYOK + cloudReady=true: being linked entitles the install to catalog
        // updates regardless of the per-tenant CLOUD/BYOK inference choice.
        server.createContext("/api/internal/cloud-link/active-runtime", exchange -> writeJson(exchange, 200, Map.of(
                "source", "BYOK",
                "cloudReady", true,
                "accessToken", "tok",
                "installId", "install-9",
                "cloudApiUrl", "https://livecontext.ai/api"
        )));

        assertThat(access.resolveActiveCloudRuntime())
                .hasValue(new CloudLlmRuntimeCredentials("tok", "install-9", "https://livecontext.ai/api"));
    }

    @Test
    @DisplayName("resolveActiveCloudRuntime: empty when this install is not linked (cloudReady=false)")
    void activeRuntimeEmptyWhenNotLinked() {
        server.createContext("/api/internal/cloud-link/active-runtime", exchange -> writeJson(exchange, 200, Map.of(
                "source", "BYOK",
                "cloudReady", false,
                "accessToken", "",
                "installId", "",
                "cloudApiUrl", ""
        )));

        assertThat(access.resolveActiveCloudRuntime()).isEmpty();
    }

    @Test
    @DisplayName("resolveActiveCloudRuntime: empty (NOT a throw) when publication-service errors - the bundle sync just skips")
    void activeRuntimeEmptyOnError() {
        server.createContext("/api/internal/cloud-link/active-runtime",
                exchange -> writeText(exchange, 503, "publication-service unavailable"));

        assertThat(access.resolveActiveCloudRuntime()).isEmpty();
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        write(exchange, status, "application/json", objectMapper.writeValueAsBytes(body));
    }

    private static void writeText(HttpExchange exchange, int status, String body) throws IOException {
        write(exchange, status, "text/plain", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
