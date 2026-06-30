package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.controller.internal.ConversationToolExecutionController;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP wire-contract integration test for the internal tool-execution endpoint that
 * agent-service calls back to ({@code POST /api/internal/conversation/tools/execute}).
 *
 * <p>Wires the REAL {@link ConversationToolExecutionController} to the REAL
 * {@link ConversationToolExecutionService} (only the auth-service credential-lookup seam
 * {@code findExistingCredential} is spied), so it exercises request parsing, the
 * request_credential branch logic, and the JSON serialization the bridge/agent-service
 * actually consume - the layer the pure-service unit test skips.
 *
 * <p>Lives in the {@code service.ai} package (not with the controller) on purpose: the
 * stubbed seam {@code findExistingCredential} is package-private, so the spy must be set
 * up from this package rather than widening main-code visibility for tests.
 *
 * <p>Pins the post-fix contract for {@code request_credential} on an ALREADY-connected
 * service: without {@code force} → a hard error whose guidance is in {@code error} (NOT
 * {@code result}/content, since the Claude Code bridge forwards only content+error) and
 * NO approval card ({@code serviceApprovalRequested} absent); with {@code force=true} →
 * the reconnect card ({@code serviceApprovalRequested}+{@code needsAttention}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationToolExecutionController - request_credential wire contract")
class ConversationToolExecutionControllerIntegrationTest {

    @Mock
    private ConversationHistoryService conversationHistoryService;
    @Mock
    private ToolResultService toolResultService;
    @Mock
    private StreamPubSubService streamPubSubService;

    private ConversationToolExecutionService service;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        ToolServiceRouter toolServiceRouter = new ToolServiceRouter(
            "http://localhost:8099", "http://localhost:8090",
            "http://localhost:8088", "http://localhost:8089", "http://localhost:8081");

        service = spy(new ConversationToolExecutionService(
            conversationHistoryService, toolResultService, streamPubSubService, toolServiceRouter));

        setField("orchestratorUrl", "http://localhost:8099");
        setField("mcpGatewayUrl", "http://localhost:8083");

        ConversationToolExecutionController controller = new ConversationToolExecutionController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ConversationToolExecutionService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    // Distinct tenantId per test: the force-loop anti-nag guard is a static map keyed by
    // (scope=tenantId, serviceType). A unique tenant per test isolates the force test from any
    // stale entry left by a sibling test class in the same surefire JVM fork - without needing
    // to clear the package-private RECENT_FORCE_REQUESTS.
    private String body(String tenantId, Map<String, Object> parameters) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tool", "request_credential");
        request.put("toolCallId", "call-1");
        request.put("tenantId", tenantId);
        request.put("parameters", parameters);
        return objectMapper.writeValueAsString(request);
    }

    @Test
    @DisplayName("existing credential, no force → 200 with success=false, guidance in error (not result), no approval card")
    void existingWithoutForceReturnsHardErrorOverHttp() throws Exception {
        doReturn(Map.of("id", "cred-1", "last_used", "2026-04-08T10:00:00Z"))
            .when(service).findExistingCredential("gmail", "tenant-itc-noforce");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("services", List.of("gmail"));
        params.put("reason", "Send email");

        mockMvc.perform(post("/api/internal/conversation/tools/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("tenant-itc-noforce", params)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            // Guidance reaches the LLM via `error` (the bridge forwards content+error).
            .andExpect(jsonPath("$.error", containsString("already exist")))
            .andExpect(jsonPath("$.error", containsString("force=true")))
            // `result` (content) is omitted → guidance did NOT leak into content.
            .andExpect(jsonPath("$.result").doesNotExist())
            // No card: serviceApprovalRequested is the sole card trigger downstream.
            .andExpect(jsonPath("$.metadata.serviceApprovalRequested").doesNotExist())
            .andExpect(jsonPath("$.metadata.needsAttention").doesNotExist())
            .andExpect(jsonPath("$.metadata.silentError").value(true))
            .andExpect(jsonPath("$.metadata.exists").value(true));
    }

    @Test
    @DisplayName("existing credential, force=true → 200 with success=true and reconnect card (needsAttention)")
    void existingWithForceReturnsReconnectCardOverHttp() throws Exception {
        doReturn(Map.of("id", "cred-1", "last_used", "2026-01-01T00:00:00Z"))
            .when(service).findExistingCredential("gmail", "tenant-itc-force");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("services", List.of("gmail"));
        params.put("reason", "Token rejected");
        params.put("force", true);

        mockMvc.perform(post("/api/internal/conversation/tools/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("tenant-itc-force", params)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.metadata.serviceApprovalRequested").value(true))
            .andExpect(jsonPath("$.metadata.needsAttention").value(true));
    }
}
