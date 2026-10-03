package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.common.web.GatewaySignatureV2;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * CASA LC-013 / LC-035: the conversation relay signs the /api/agent-tools/execute call (v1 + v2)
 * over the identity, workspace and role headers it sends, and the internal credential calls too.
 */
@DisplayName("ConversationToolExecutionService signs its internal calls (LC-013, LC-035)")
class ConversationToolExecutionServiceV2SigningTest {

    private static final String SECRET = "conv-secret";
    private ConversationToolExecutionService service;
    private MockRestServiceServer server;
    private final AtomicReference<HttpHeaders> headers = new AtomicReference<>();
    private final AtomicReference<URI> uri = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ToolServiceRouter router = new ToolServiceRouter("http://orch:8099", "http://agent:8090",
                "http://ds:8088", "http://iface:8089", "http://catalog:8081");
        service = new ConversationToolExecutionService(mock(ConversationHistoryService.class),
                mock(ToolResultService.class), mock(StreamPubSubService.class), router);
        ReflectionTestUtils.setField(service, "gatewaySecretKey", SECRET);
        ReflectionTestUtils.setField(service, "authServiceUrl", "http://auth:8083");
        server = MockRestServiceServer.bindTo(
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate")).build();
    }

    private void capture(String body) {
        server.expect(req -> {
                    headers.set(req.getHeaders());
                    uri.set(req.getURI());
                    method.set(req.getMethod().name());
                })
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private boolean v2Verifies(HttpHeaders h) {
        return GatewaySignatureV2.verify(SECRET, method.get(), uri.get().getRawPath(), uri.get().getRawQuery(),
                h::get, h.getFirst(GatewaySignatureV2.HEADER), 60_000, System.currentTimeMillis());
    }

    @Test
    @DisplayName("tool relay: signed over X-User-ID, X-Organization-ID, X-Organization-Role, X-User-Roles")
    void toolRelayIsSigned() {
        capture("{\"success\":true,\"data\":{}}");
        ReflectionTestUtils.invokeMethod(service, "executeCoreTools",
                new ToolCall("c1", "table", Map.of("action", "list"), null), "42",
                Map.of("__orgId__", "org-1", "__orgRole__", "MEMBER", "__userRoles__", "USER"),
                System.currentTimeMillis());

        assertThat(uri.get().getPath()).isEqualTo("/api/agent-tools/execute");
        assertThat(headers.get().getFirst("X-User-Roles")).isEqualTo("USER");
        assertThat(headers.get().getFirst("X-Gateway-Secret")).startsWith("gw_");
        assertThat(v2Verifies(headers.get())).isTrue();
        HttpHeaders forged = new HttpHeaders();
        headers.get().forEach(forged::addAll);
        forged.set("X-User-Roles", "ADMIN");
        assertThat(v2Verifies(forged)).as("the role is inside v2").isFalse();
    }

    @Test
    @DisplayName("internal credential lookup is signed with v1 + v2")
    void credentialLookupIsSigned() {
        capture("{}");
        service.findExistingCredential("gmail", "42");
        assertThat(uri.get().getPath()).isEqualTo("/api/internal/credentials/default");
        assertThat(headers.get().get("X-User-ID")).isEqualTo(List.of("42"));
        assertThat(v2Verifies(headers.get())).isTrue();
    }
}
