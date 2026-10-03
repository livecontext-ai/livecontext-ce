package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.common.web.BridgeRequestSignature;
import com.apimarketplace.common.web.InternalGatewaySigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-001 / LC-022 regression: the bridge rejects an unsigned {@code POST /api/bridge/execute}
 * (401), checks the body-bound {@code X-Bridge-Signature} against the raw bytes it receives, and
 * grants the unrestricted toolset only to a body-bound signature with the unrestricted provider
 * id. Pre-fix this client sent only X-User-ID / X-Organization-ID, so every dispatch against an
 * enforcing bridge came back null (an empty chat, no error). The test drives the real HTTP path
 * against a local listener and recomputes both signatures from the headers and the exact bytes
 * that arrived.
 */
@DisplayName("SubAgentBridgeClient - signed bridge dispatch (LC-001 / LC-022)")
class SubAgentBridgeClientGatewaySignatureTest {

    private static final String SECRET = "test-gateway-hmac-key-for-unit-tests";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<Map<String, List<String>>> captured = new AtomicReference<>();
    private final AtomicReference<byte[]> capturedBody = new AtomicReference<>();
    private String bridgeUrl;

    @BeforeEach
    void startBridge() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/bridge/execute", exchange -> {
            captured.set(new HashMap<>(exchange.getRequestHeaders()));
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        bridgeUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopBridge() {
        server.stop(0);
    }

    private static AgentExecutionRequestDto request(Map<String, Object> credentials) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", "hi");
        body.put("provider", "claude-code");
        body.put("tenantId", "tenant-1");
        if (credentials != null) body.put("credentials", credentials);
        return MAPPER.convertValue(body, AgentExecutionRequestDto.class);
    }

    private List<String> headerValues(String name) {
        return captured.get().entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst().orElse(null);
    }

    private String header(String name) {
        List<String> v = headerValues(name);
        return v == null ? null : v.get(0);
    }

    private void assertValidSignatures(String expectedProviderId) throws Exception {
        assertThat(header("X-Provider-ID")).isEqualTo(expectedProviderId);
        String timestamp = header("X-Gateway-Timestamp");
        assertThat(timestamp).isNotBlank();
        assertThat(header("X-Gateway-Secret")).isEqualTo(InternalGatewaySigner.sign(
            expectedProviderId, header("X-User-ID"), header("X-Organization-ID"), timestamp, SECRET));
        assertThat(header("X-Bridge-Signature")).isEqualTo(BridgeRequestSignature.sign(
            SECRET, "POST", "/api/bridge/execute", null, this::headerValues, timestamp, capturedBody.get()));
        assertThat(header("X-User-ID")).isEqualTo("tenant-1");
        JsonNode sent = MAPPER.readTree(capturedBody.get());
        assertThat(sent.get("tenantId").asText()).as("body identity matches the signed one").isEqualTo("tenant-1");
        assertThat(sent.get("prompt").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("every dispatch carries v1 + a body signature over the exact bytes sent, restricted by default")
    void dispatchIsSigned() throws Exception {
        new SubAgentBridgeClient(bridgeUrl, SECRET, false).execute(request(null), "ADMIN");
        assertValidSignatures("bridge-client");
    }

    @Test
    @DisplayName("host tools allowed + platform ADMIN caller: signed unrestricted")
    void adminGetsUnrestricted() throws Exception {
        new SubAgentBridgeClient(bridgeUrl, SECRET, true).execute(request(null), "ADMIN,USER");
        assertValidSignatures("bridge-unrestricted");
    }

    @Test
    @DisplayName("host tools allowed but a non-admin (or no roles in scope): restricted")
    void nonAdminStaysRestricted() throws Exception {
        new SubAgentBridgeClient(bridgeUrl, SECRET, true).execute(request(null), "USER");
        assertValidSignatures("bridge-client");
        new SubAgentBridgeClient(bridgeUrl, SECRET, true).execute(request(null), null);
        assertValidSignatures("bridge-client");
    }

    @Test
    @DisplayName("restricted API-mode request is never signed unrestricted, even for an admin")
    void restrictedClaimNeverWidened() throws Exception {
        new SubAgentBridgeClient(bridgeUrl, SECRET, true).execute(request(Map.of("__restrictedToolset__", true)), "ADMIN");
        assertValidSignatures("bridge-client");
    }

    @Test
    @DisplayName("blank secret (enforcement-off launchers): dispatch still sent, unsigned")
    void blankSecretUnsigned() {
        new SubAgentBridgeClient(bridgeUrl, "", true).execute(request(null), "ADMIN");
        assertThat(captured.get()).isNotNull();
        assertThat(header("X-Gateway-Secret")).isNull();
        assertThat(header("X-Bridge-Signature")).isNull();
    }
}
