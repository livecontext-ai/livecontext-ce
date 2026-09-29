package com.apimarketplace.conversation.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The agent loop's tool-result save. It moved from the user-facing {@code POST /api/tool-results}
 * (now scope-checked against the caller's workspace, and no longer a gateway public path) to the
 * service-to-service {@code POST /api/internal/tool-results}; for one release it falls back to
 * the old route when an older conversation-service answers 404 on the new one.
 */
@DisplayName("ConversationClient.saveToolResult")
class ConversationClientSaveToolResultTest {

    private static final String BASE_URL = "http://conversation-service:8087";
    private static final String INTERNAL_URL = BASE_URL + "/api/internal/tool-results";
    private static final String LEGACY_URL = BASE_URL + "/api/tool-results";

    private MockRestServiceServer server;
    private ConversationClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new ConversationClient(restTemplate, BASE_URL);
    }

    @Test
    @DisplayName("regression: posts to the internal route, with the caller's identity and workspace headers")
    void postsToInternalRoute() {
        server.expect(requestTo(INTERNAL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-ID", "tenant-1"))
                .andExpect(header("X-Organization-ID", "org-1"))
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.toolName").value("table"))
                .andRespond(withSuccess("{\"id\":\"tr-1\"}", MediaType.APPLICATION_JSON));

        String id = client.saveToolResult("conv-1", "tenant-1", "table", "tc-1",
                true, 12L, "out", null, null, "org-1");

        assertThat(id).isEqualTo("tr-1");
        server.verify();
    }

    @Test
    @DisplayName("rollout: a 404 on the internal route (older conversation-service) falls back to the legacy route once")
    void fallsBackToLegacyRouteOn404() {
        server.expect(requestTo(INTERNAL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(LEGACY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-ID", "tenant-1"))
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andRespond(withSuccess("{\"id\":\"tr-legacy\"}", MediaType.APPLICATION_JSON));

        String id = client.saveToolResult("conv-1", "tenant-1", "table", "tc-1",
                true, 12L, "out", null, null, "org-1");

        assertThat(id).isEqualTo("tr-legacy");
        server.verify();
    }

    @Test
    @DisplayName("any other error on the internal route does NOT fall back (no second write attempt)")
    void noFallbackOnOtherErrors() {
        server.expect(requestTo(INTERNAL_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        String id = client.saveToolResult("conv-1", "tenant-1", "table", "tc-1",
                true, 12L, "out", null, null, "org-1");

        assertThat(id).isNull();
        server.verify();
    }

    @Test
    @DisplayName("a 404 on the legacy fallback too ends as a logged failure, not an exception")
    void legacy404IsSwallowed() {
        server.expect(requestTo(INTERNAL_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(LEGACY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        String id = client.saveToolResult("conv-1", "tenant-1", "table", "tc-1",
                true, 12L, "out", null, null, "org-1");

        assertThat(id).isNull();
        server.verify();
    }
}
