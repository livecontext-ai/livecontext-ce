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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicationCloudPlatformInfoAccess")
class PublicationCloudPlatformInfoAccessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private StubRuntimeAccess runtimeAccess;
    private PublicationCloudPlatformInfoAccess access;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        runtimeAccess = new StubRuntimeAccess();
        runtimeAccess.setActive(new CloudLlmRuntimeCredentials(
                "tok", "install-9", "http://127.0.0.1:" + server.getAddress().getPort()));
        access = new PublicationCloudPlatformInfoAccess(runtimeAccess, new RestTemplate());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("fetches the cloud platform info with the cloud-link bearer + install header and apiToolId query")
    void fetchesWithCloudLinkAuthAndToolId() {
        server.createContext("/ce-catalog/platform-info/telegram", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer tok");
            assertThat(exchange.getRequestHeaders().getFirst("X-LiveContext-Install-Id")).isEqualTo("install-9");
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("apiToolId=tool-1");
            writeJson(exchange, 200, Map.of(
                    "integrationName", "telegram",
                    "available", true,
                    "platformCredentialId", 42,
                    "hasPricing", true,
                    "markupCredits", "0.05",
                    "subscriptionActive", true,
                    "relayEligible", true
            ));
        });

        Optional<Map<String, Object>> info = access.fetchPlatformInfo("telegram", "tool-1");

        assertThat(info).isPresent();
        assertThat(info.get())
                .containsEntry("integrationName", "telegram")
                .containsEntry("available", true)
                .containsEntry("platformCredentialId", 42)
                .containsEntry("hasPricing", true)
                .containsEntry("markupCredits", "0.05")
                .containsEntry("subscriptionActive", true)
                .containsEntry("relayEligible", true);
    }

    @Test
    @DisplayName("omits the apiToolId query param when none is supplied")
    void omitsToolIdQueryWhenAbsent() {
        server.createContext("/ce-catalog/platform-info/telegram", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).isNull();
            writeJson(exchange, 200, Map.of("integrationName", "telegram", "available", false));
        });

        Optional<Map<String, Object>> info = access.fetchPlatformInfo("telegram", null);

        assertThat(info).isPresent();
        assertThat(info.get()).containsEntry("available", false);
    }

    @Test
    @DisplayName("empty when the install has no active catalog cloud runtime (unlinked or BYOK catalog source)")
    void emptyWithoutActiveCatalogRuntime() {
        runtimeAccess.setActive(null);

        assertThat(access.fetchPlatformInfo("telegram", null)).isEmpty();
    }

    @Test
    @DisplayName("empty (NOT a throw) on a non-2xx cloud response - fail-closed")
    void emptyOnNon2xx() {
        server.createContext("/ce-catalog/platform-info/telegram",
                exchange -> writeText(exchange, 403, "{\"error\":\"CE_LINK_NOT_ACTIVE\"}"));

        assertThat(access.fetchPlatformInfo("telegram", null)).isEmpty();
    }

    @Test
    @DisplayName("empty (NOT a throw) when the cloud is unreachable - fail-closed")
    void emptyOnTransportFailure() {
        // Point the runtime at a port with no listener.
        runtimeAccess.setActive(new CloudLlmRuntimeCredentials("tok", "install-9", "http://127.0.0.1:1"));

        assertThat(access.fetchPlatformInfo("telegram", null)).isEmpty();
    }

    @Test
    @DisplayName("empty for a blank integration name (never calls the cloud)")
    void emptyForBlankIntegrationName() {
        assertThat(access.fetchPlatformInfo(" ", null)).isEmpty();
        assertThat(access.fetchPlatformInfo(null, null)).isEmpty();
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

    /**
     * Hand-rolled stub (not Mockito) so this test compiles independently of the exact
     * shape of the parallel {@code resolveActiveCatalogRuntime} interface addition.
     */
    private static final class StubRuntimeAccess implements CloudLlmRuntimeAccess {
        private Optional<CloudLlmRuntimeCredentials> active = Optional.empty();

        void setActive(CloudLlmRuntimeCredentials credentials) {
            this.active = Optional.ofNullable(credentials);
        }

        @Override
        public CloudLlmSource getEffectiveLlmSource(String tenantId) {
            return CloudLlmSource.BYOK;
        }

        @Override
        public Optional<CloudLlmRuntimeCredentials> resolveCloudRuntime(String tenantId) {
            return Optional.empty();
        }

        public Optional<CloudLlmRuntimeCredentials> resolveActiveCatalogRuntime() {
            return active;
        }
    }
}
