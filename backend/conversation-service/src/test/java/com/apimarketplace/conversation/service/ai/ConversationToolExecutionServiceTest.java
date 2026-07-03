package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("ConversationToolExecutionService")
@ExtendWith(MockitoExtension.class)
class ConversationToolExecutionServiceTest {

    @Mock
    private ConversationHistoryService conversationHistoryService;

    @Mock
    private ToolResultService toolResultService;

    @Mock
    private StreamPubSubService streamPubSubService;

    private ConversationToolExecutionService service;

    @BeforeEach
    void setUp() throws Exception {
        // Anti-loop guard uses a static map - clear between tests so they are isolated.
        ConversationToolExecutionService.RECENT_FORCE_REQUESTS.clear();

        ToolServiceRouter toolServiceRouter = new ToolServiceRouter(
            "http://localhost:8099", "http://localhost:8090",
            "http://localhost:8088", "http://localhost:8089", "http://localhost:8081");

        service = spy(new ConversationToolExecutionService(
            conversationHistoryService, toolResultService,
            streamPubSubService, toolServiceRouter));

        Field orchestratorUrlField = ConversationToolExecutionService.class.getDeclaredField("orchestratorUrl");
        orchestratorUrlField.setAccessible(true);
        orchestratorUrlField.set(service, "http://localhost:8099");

        Field mcpGatewayUrlField = ConversationToolExecutionService.class.getDeclaredField("mcpGatewayUrl");
        mcpGatewayUrlField.setAccessible(true);
        mcpGatewayUrlField.set(service, "http://localhost:8083");

        Field authServiceUrlField = ConversationToolExecutionService.class.getDeclaredField("authServiceUrl");
        authServiceUrlField.setAccessible(true);
        authServiceUrlField.set(service, "http://localhost:8083");
    }

    /**
     * Binds a MockRestServiceServer to the service's internally-constructed
     * RestTemplate so the auth-service internal endpoints (credentials/all,
     * variables/list, variables/set) can be stubbed without a live server.
     */
    private MockRestServiceServer bindAuthServiceServer() throws Exception {
        Field restTemplateField = ConversationToolExecutionService.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        RestTemplate restTemplate = (RestTemplate) restTemplateField.get(service);
        return MockRestServiceServer.bindTo(restTemplate).build();
    }

    private ToolCall createToolCall(String id, String toolName, Map<String, Object> arguments) {
        return new ToolCall(id, toolName, arguments, null);
    }

    private ToolDefinition createToolDefinition(String name) {
        return ToolDefinition.builder()
                .name(name)
                .description("Test tool")
                .build();
    }

    @Nested
    @DisplayName("set_conversation_title")
    class SetConversationTitleTests {

        @Test
        @DisplayName("should set title successfully")
        void shouldSetTitleSuccessfully() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", "My Conversation"));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("My Conversation");
            assertThat(result.content()).contains("conv-1");
            verify(conversationHistoryService).updateConversationTitle("conv-1", "tenant-1", null, "My Conversation");
        }

        @Test
        @DisplayName("should truncate long titles to 50 characters")
        void shouldTruncateLongTitles() {
            String longTitle = "A".repeat(60);
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", longTitle));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isTrue();
            verify(conversationHistoryService).updateConversationTitle(eq("conv-1"), eq("tenant-1"), eq(null),
                    argThat(title -> title.length() <= 50 && title.endsWith("...")));
        }

        @Test
        @DisplayName("should fail when title is null")
        void shouldFailWhenTitleNull() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    new HashMap<>());
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Title is required");
        }

        @Test
        @DisplayName("should fail when title is empty")
        void shouldFailWhenTitleEmpty() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", ""));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Title is required");
        }

        @Test
        @DisplayName("should fail when conversationId is missing from credentials")
        void shouldFailWhenConversationIdMissing() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", "Test"));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of();

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("conversationId not available");
        }

        @Test
        @DisplayName("should reject file path titles")
        void shouldRejectFilePathTitles() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", "C:\\Users\\test\\file.exe"));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            // Should succeed with fallback (returns success to avoid agent retry)
            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("Title pending");
        }

        @Test
        @DisplayName("should reject Java classpath titles")
        void shouldRejectClasspathTitles() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", "-Dspring.profiles.active=dev -classpath /lib"));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("Title pending");
        }

        @Test
        @DisplayName("should reject titles that are too long (>200 chars)")
        void shouldRejectTooLongTitles() {
            String veryLongTitle = "A".repeat(201);
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", veryLongTitle));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("Title pending");
        }

        @Test
        @DisplayName("should clean title by removing surrounding quotes")
        void shouldCleanTitle() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", "\"My Title\""));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isTrue();
            verify(conversationHistoryService).updateConversationTitle("conv-1", "tenant-1", null, "My Title");
        }
    }

    @Nested
    @DisplayName("get_tool_result")
    class GetToolResultTests {

        @Test
        @DisplayName("should retrieve tool result successfully")
        void shouldRetrieveToolResult() {
            ToolCall toolCall = createToolCall("call_1", "get_tool_result",
                    Map.of("tool_call_id", "call_prev_1"));
            ToolDefinition toolDef = createToolDefinition("get_tool_result");

            var savedResult = mock(com.apimarketplace.conversation.entity.ToolResult.class);
            when(savedResult.getToolName()).thenReturn("catalog");
            when(savedResult.isSuccess()).thenReturn(true);
            when(savedResult.getContentFull()).thenReturn("{\"data\": \"test\"}");
            when(savedResult.getErrorMessage()).thenReturn(null);

            when(toolResultService.getByToolCallId("call_prev_1", "tenant-1", null))
                    .thenReturn(Optional.of(savedResult));

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("call_prev_1");
            assertThat(result.content()).contains("catalog");
        }

        @Test
        @DisplayName("should fail when tool_call_id is missing")
        void shouldFailWhenToolCallIdMissing() {
            ToolCall toolCall = createToolCall("call_1", "get_tool_result", new HashMap<>());
            ToolDefinition toolDef = createToolDefinition("get_tool_result");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("tool_call_id is required");
        }

        @Test
        @DisplayName("should fail when tool result not found")
        void shouldFailWhenNotFound() {
            ToolCall toolCall = createToolCall("call_1", "get_tool_result",
                    Map.of("tool_call_id", "nonexistent"));
            ToolDefinition toolDef = createToolDefinition("get_tool_result");

            when(toolResultService.getByToolCallId("nonexistent", "tenant-1", null))
                    .thenReturn(Optional.empty());

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Tool result not found");
        }

        @Test
        @DisplayName("should include error message in result when present")
        void shouldIncludeErrorMessage() {
            ToolCall toolCall = createToolCall("call_1", "get_tool_result",
                    Map.of("tool_call_id", "call_err"));
            ToolDefinition toolDef = createToolDefinition("get_tool_result");

            var savedResult = mock(com.apimarketplace.conversation.entity.ToolResult.class);
            when(savedResult.getToolName()).thenReturn("some_tool");
            when(savedResult.isSuccess()).thenReturn(false);
            when(savedResult.getContentFull()).thenReturn(null);
            when(savedResult.getErrorMessage()).thenReturn("execution failed");

            when(toolResultService.getByToolCallId("call_err", "tenant-1", null))
                    .thenReturn(Optional.of(savedResult));

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("execution failed");
        }
    }

    /**
     * The require flow, exercised through the LEGACY {@code request_credential}
     * alias (pre-rename sessions). The alias must keep routing to the exact
     * same behavior as {@code credential(action='require')} - these tests pin
     * that the rename did not change the require semantics for old sessions.
     */
    @Nested
    @DisplayName("require flow via legacy request_credential alias")
    class RequestCredentialTests {

        @Test
        @DisplayName("should fail when services parameter is missing")
        void shouldFailWhenServicesMissing() {
            ToolCall toolCall = createToolCall("call_1", "request_credential",
                    Map.of("reason", "Need access"));
            ToolDefinition toolDef = createToolDefinition("request_credential");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'services' parameter is required");
        }

        @Test
        @DisplayName("should handle simple service type strings")
        void shouldHandleSimpleServiceStrings() {
            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail", "slack"));
            args.put("reason", "Need to send emails");

            ToolCall toolCall = createToolCall("call_1", "request_credential", args);
            ToolDefinition toolDef = createToolDefinition("request_credential");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("credentials_required");
            assertThat(result.metadata()).containsKey("serviceApprovalRequested");
            assertThat(result.metadata().get("serviceApprovalRequested")).isEqualTo(true);
        }

        @Test
        @DisplayName("should handle service map objects")
        void shouldHandleServiceMaps() {
            Map<String, Object> gmailService = new HashMap<>();
            gmailService.put("serviceType", "gmail");
            gmailService.put("serviceName", "Gmail");

            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of(gmailService));
            args.put("reason", "Need Gmail access");

            ToolCall toolCall = createToolCall("call_1", "request_credential", args);
            ToolDefinition toolDef = createToolDefinition("request_credential");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).containsKey("services");
        }

        @Test
        @DisplayName("should fail when services list is empty after parsing")
        void shouldFailWhenServicesEmpty() {
            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of());
            args.put("reason", "Need access");

            ToolCall toolCall = createToolCall("call_1", "request_credential", args);
            ToolDefinition toolDef = createToolDefinition("request_credential");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("At least one service is required");
        }

        @Test
        @DisplayName("should accept force=true and produce serviceApprovalRequested metadata")
        void shouldAcceptForceTrue() {
            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail"));
            args.put("reason", "Token rejected");
            args.put("force", true);

            ToolCall toolCall = createToolCall("call_1", "request_credential", args);
            ToolDefinition toolDef = createToolDefinition("request_credential");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            // In the unit test environment the credential lookup fails (no orchestrator),
            // so gmail is treated as missing → normal connect card, needsAttention NOT set.
            // The "needsAttention via force on existing credential" path requires a live
            // orchestrator and is covered by integration tests.
            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).containsKey("serviceApprovalRequested");
            assertThat(result.metadata()).doesNotContainKey("needsAttention");
        }

        @Test
        @DisplayName("existing credential without force → hard 'already exist' error with force=true escalation hint (no card)")
        void shouldReturnAlreadyExistsErrorWhenExistsWithoutForce() {
            doReturn(Map.of("id", "cred-1", "last_used", "2026-04-08T10:00:00Z"))
                .when(service).findExistingCredential("gmail", "tenant-1");

            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail"));
            args.put("reason", "Send email");

            ToolResult result = service.executeTool(
                createToolCall("call_1", "request_credential", args),
                createToolDefinition("request_credential"), "tenant-1", Map.of());

            // HARD error (success=false) so the agent treats "already exists" as a blocker
            // and does NOT spam the user with an approval card. The actionable guidance
            // lives in `error` (which the Claude Code bridge forwards to the LLM history),
            // and `metadata.silentError=true` tells the frontend NOT to render a card.
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("already exist");
            assertThat(result.error()).contains("force=true");
            // Guidance MUST live in `error` (which the bridge forwards), NOT in `content` -
            // pins the regression vector that re-broke this in the past (success=true + JSON content).
            assertThat(result.content()).isNull();
            assertThat(result.metadata()).containsEntry("silentError", true);
            assertThat(result.metadata()).containsEntry("exists", true);
            assertThat(result.metadata()).doesNotContainKey("serviceApprovalRequested");
            assertThat(result.metadata()).doesNotContainKey("needsAttention");
        }

        @Test
        @DisplayName("existing credential with force=true → needsAttention=true (warning card)")
        void shouldFlagNeedsAttentionWhenForcedOnExisting() {
            doReturn(Map.of("id", "cred-1", "last_used", "2026-01-01T00:00:00Z"))
                .when(service).findExistingCredential("gmail", "tenant-1");

            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail"));
            args.put("reason", "Token rejected");
            args.put("force", true);

            ToolResult result = service.executeTool(
                createToolCall("call_1", "request_credential", args),
                createToolDefinition("request_credential"), "tenant-1", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).containsEntry("serviceApprovalRequested", true);
            assertThat(result.metadata()).containsEntry("needsAttention", true);
        }

        @Test
        @DisplayName("force=true accepted as string \"true\" (LLM serialization tolerance)")
        void shouldAcceptForceAsStringTrue() {
            doReturn(Map.of("id", "cred-1")).when(service).findExistingCredential("gmail", "tenant-1");

            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail"));
            args.put("reason", "Token rejected");
            args.put("force", "true");

            ToolResult result = service.executeTool(
                createToolCall("call_1", "request_credential", args),
                createToolDefinition("request_credential"), "tenant-1", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).containsEntry("needsAttention", true);
        }

        @Test
        @DisplayName("anti-loop: second forced reconnect within cooldown is blocked")
        void shouldBlockRepeatedForcedReconnect() {
            doReturn(Map.of("id", "cred-1")).when(service).findExistingCredential("gmail", "tenant-1");

            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail"));
            args.put("reason", "Token rejected");
            args.put("force", true);

            // First forced call goes through
            ToolResult first = service.executeTool(
                createToolCall("call_1", "request_credential", args),
                createToolDefinition("request_credential"), "tenant-1", Map.of());
            assertThat(first.success()).isTrue();
            assertThat(first.metadata()).containsEntry("needsAttention", true);

            // Second within cooldown is blocked
            ToolResult second = service.executeTool(
                createToolCall("call_2", "request_credential", args),
                createToolDefinition("request_credential"), "tenant-1", Map.of());
            assertThat(second.success()).isFalse();
            assertThat(second.metadata()).containsEntry("forceLoopBlocked", List.of("gmail"));
            assertThat(second.error()).contains("already requested a forced reconnect");
            assertThat(second.error()).contains("NOT a credential problem");
        }

        @Test
        @DisplayName("partial mix without force → only missing services are requested, no needsAttention")
        void shouldRequestOnlyMissingOnPartialMix() {
            doReturn(Map.of("id", "cred-1")).when(service).findExistingCredential("gmail", "tenant-1");
            doReturn(null).when(service).findExistingCredential("slack", "tenant-1");

            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail", "slack"));
            args.put("reason", "Need both");

            ToolResult result = service.executeTool(
                createToolCall("call_1", "request_credential", args),
                createToolDefinition("request_credential"), "tenant-1", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).doesNotContainKey("needsAttention");
            // Only slack should remain in the services list (gmail was filtered out)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> services = (List<Map<String, Object>>) result.metadata().get("services");
            assertThat(services).hasSize(1);
            assertThat(services.get(0)).containsEntry("serviceType", "slack");
        }

        @Test
        @DisplayName("should NOT bypass credential check based on reason keywords (keyword sniffing removed)")
        void shouldNotSniffReasonKeywords() {
            // Old behavior: a reason containing "expired"/"reconnect"/"401" used to
            // bypass the credential check. New design: only force=true does that, and
            // only when the credential actually exists.
            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail"));
            args.put("reason", "Token expired, please reconnect (401)");

            ToolCall toolCall = createToolCall("call_1", "request_credential", args);
            ToolDefinition toolDef = createToolDefinition("request_credential");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            // With no orchestrator in tests, lookup returns null → treated as missing
            // → normal flow → needsAttention is never set from reason keywords.
            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).doesNotContainKey("needsAttention");
        }

        @Test
        @DisplayName("should include icon and display name in metadata")
        void shouldIncludeDisplayInfo() {
            Map<String, Object> args = new HashMap<>();
            args.put("services", List.of("gmail"));
            args.put("reason", "Need Gmail");

            ToolCall toolCall = createToolCall("call_1", "request_credential", args);
            ToolDefinition toolDef = createToolDefinition("request_credential");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).containsKey("reason");
        }
    }

    @Nested
    @DisplayName("credential - unified action dispatch")
    class CredentialDispatchTests {

        private ToolResult execute(String toolName, Map<String, Object> args, Map<String, Object> credentials) {
            return service.executeTool(
                createToolCall("call_1", toolName, args),
                createToolDefinition(toolName), "tenant-1", credentials);
        }

        // ── action routing ───────────────────────────────────────────────────

        @Test
        @DisplayName("credential(action='require') routes to the require flow (same validation as the legacy alias)")
        void requireActionRoutesToRequireFlow() {
            Map<String, Object> args = new HashMap<>();
            args.put("action", "require");
            args.put("reason", "Need access"); // no services → require-flow error

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'services' parameter is required");
        }

        @Test
        @DisplayName("credential(action='require', services=[...]) produces the approval card like the legacy alias")
        void requireActionProducesApprovalCard() {
            Map<String, Object> args = new HashMap<>();
            args.put("action", "require");
            args.put("services", List.of("gmail"));
            args.put("reason", "Need to send emails");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("credentials_required");
            assertThat(result.metadata()).containsEntry("serviceApprovalRequested", true);
        }

        @Test
        @DisplayName("action is case-insensitive and trimmed")
        void actionIsNormalized() {
            Map<String, Object> args = new HashMap<>();
            args.put("action", "  REQUIRE ");
            args.put("reason", "Need access");

            ToolResult result = execute("credential", args, Map.of());

            // Reached the require flow (its own validation fired), not the unknown-action error.
            assertThat(result.error()).contains("'services' parameter is required");
        }

        @Test
        @DisplayName("unknown action → error listing the valid actions")
        void unknownActionListsValidActions() {
            ToolResult result = execute("credential", Map.of("action", "rotate"), Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("list, variables, set_variable, require");
        }

        @Test
        @DisplayName("missing action → same error listing the valid actions")
        void missingActionListsValidActions() {
            ToolResult result = execute("credential", new HashMap<>(), Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("list, variables, set_variable, require");
        }

        @Test
        @DisplayName("legacy request_credential toolName forces the require action even if args carry another action")
        void legacyAliasAlwaysRoutesToRequire() {
            Map<String, Object> args = new HashMap<>();
            args.put("action", "list"); // must be IGNORED for the legacy name
            args.put("reason", "Need access");

            ToolResult result = execute("request_credential", args, Map.of());

            // Require-flow validation fired → proof the alias never dispatches to list.
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'services' parameter is required");
        }

        // ── action=list ──────────────────────────────────────────────────────

        @Test
        @DisplayName("list maps auth-service rows to {connected,count,defaultCount,hint} WITHOUT leaking credential_data secrets")
        void listMapsRowsWithoutLeakingSecrets() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            String rows = """
                [
                  {"name":"Gmail","integration":"gmail","status":"ACTIVE","is_default":true,
                   "credential_data":{"access_token":"SECRET_TOKEN_XYZ_123","refresh_token":"SECRET_REFRESH_ABC","email":"user@example.com"}},
                  {"name":"Slack","integration":"slack","status":"NEEDS_REAUTH","is_default":false,
                   "credential_data":{"api_key":"SECRET_SLACK_KEY_456"}}
                ]
                """;
            server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Organization-ID", "org-1"))
                .andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));

            ToolResult result = execute("credential", Map.of("action", "list"),
                Map.of("__orgId__", "org-1"));

            assertThat(result.success()).isTrue();
            String content = result.content();
            // Allowlisted fields survive.
            assertThat(content)
                .contains("\"name\":\"Gmail\"")
                .contains("\"integration\":\"gmail\"")
                .contains("\"status\":\"active\"")
                .contains("\"status\":\"needs_reauth\"")
                .contains("\"isDefault\":true")
                .contains("\"account\":\"user@example.com\"")
                .contains("\"count\":2")
                .contains("\"defaultCount\":1")
                .contains("hint");
            // The token material planted in credential_data must NEVER reach the LLM.
            assertThat(content)
                .doesNotContain("SECRET_TOKEN_XYZ_123")
                .doesNotContain("SECRET_REFRESH_ABC")
                .doesNotContain("SECRET_SLACK_KEY_456")
                .doesNotContain("credential_data")
                .doesNotContain("access_token");
            server.verify();
        }

        @Test
        @DisplayName("list with no connected services → count 0 and the 'connect one first' hint")
        void listEmptyGivesGuidanceHint() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andExpect(headerDoesNotExist("X-Organization-ID"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

            ToolResult result = execute("credential", Map.of("action", "list"), Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.content())
                .contains("\"count\":0")
                .contains("No services connected yet");
            server.verify();
        }

        @Test
        @DisplayName("list surfaces a transport failure as a tool error, not an exception")
        void listRelaysTransportFailureAsError() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

            ToolResult result = execute("credential", Map.of("action", "list"), Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Could not list connected services");
        }

        // ── action=variables ─────────────────────────────────────────────────

        @Test
        @DisplayName("variables returns the auth-service list plus count and the {{$vars.name}} usage hint")
        void variablesReturnsListWithUsageHint() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            String body = """
                {"variables":[{"name":"api_url","value":"https://x","type":"STRING","scope":"personal","description":null}],
                 "count":1}
                """;
            server.expect(requestTo("http://localhost:8083/api/internal/variables/list?tenantId=tenant-1"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Organization-ID", "org-1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            ToolResult result = execute("credential", Map.of("action", "variables"),
                Map.of("__orgId__", "org-1"));

            assertThat(result.success()).isTrue();
            assertThat(result.content())
                .contains("\"api_url\"")
                .contains("\"count\":1")
                .contains("{{$vars.name}}")
                .contains("set_variable");
            server.verify();
        }

        @Test
        @DisplayName("variables surfaces a transport failure as a tool error")
        void variablesRelaysTransportFailureAsError() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/list?tenantId=tenant-1"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

            ToolResult result = execute("credential", Map.of("action", "variables"), Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Could not list workflow variables");
        }

        // ── action=set_variable ──────────────────────────────────────────────

        @Test
        @DisplayName("set_variable posts the payload with X-Organization-ID from credentials.__orgId__ and returns saved+reference")
        void setVariablePostsPayloadWithOrgHeader() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Organization-ID", "org-1"))
                .andExpect(jsonPath("$.name").value("api_base_url"))
                .andExpect(jsonPath("$.value").value("https://api.example.com"))
                // No type arg -> the key must be ABSENT (see the retype
                // regression test below), never an injected STRING default.
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.description").value("base url"))
                .andRespond(withSuccess(
                    "{\"id\":1,\"name\":\"api_base_url\",\"value\":\"https://api.example.com\",\"type\":\"STRING\",\"scope\":\"workspace\"}",
                    MediaType.APPLICATION_JSON));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", " api_base_url "); // trimmed before sending
            args.put("value", "https://api.example.com");
            args.put("description", "base url");

            ToolResult result = execute("credential", args, Map.of("__orgId__", "org-1"));

            assertThat(result.success()).isTrue();
            assertThat(result.content())
                .contains("\"saved\"")
                .contains("{{$vars.api_base_url}}")
                .contains("hint");
            server.verify();
        }

        @Test
        @DisplayName("REGRESSION (VIEWER write bypass): set_variable forwards X-Organization-Role when credentials carry __orgRole__ - the internal VIEWER gate depends on it")
        void setVariableForwardsOrgRoleHeader() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Organization-ID", "org-1"))
                .andExpect(header("X-Organization-Role", "MEMBER"))
                // MonolithOrganizationContextFilter strips org headers without a
                // user identity - X-User-ID must accompany them.
                .andExpect(header("X-User-ID", "tenant-1"))
                .andRespond(withSuccess("{\"id\":1,\"name\":\"api_url\"}", MediaType.APPLICATION_JSON));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "api_url");
            args.put("value", "https://x");

            ToolResult result = execute("credential", args,
                Map.of("__orgId__", "org-1", "__orgRole__", "MEMBER"));

            assertThat(result.success()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("set_variable sends NO X-Organization-Role header when __orgRole__ is absent (personal scope)")
        void setVariableOmitsOrgRoleHeaderWhenAbsent() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andExpect(headerDoesNotExist("X-Organization-Role"))
                .andExpect(headerDoesNotExist("X-Organization-ID"))
                .andRespond(withSuccess("{\"id\":1,\"name\":\"api_url\"}", MediaType.APPLICATION_JSON));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "api_url");
            args.put("value", "https://x");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("set_variable relays the 403 org_role_read_only message verbatim so a VIEWER's agent sees the do-not-retry guidance")
        void setVariableRelays403ViewerMessage() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"org_role_read_only\",\"message\":\"Viewers cannot modify workspace variables. "
                        + "Tell the user their workspace role is read-only. DO NOT RETRY.\"}"));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "api_url");
            args.put("value", "https://x");

            ToolResult result = execute("credential", args,
                Map.of("__orgId__", "org-1", "__orgRole__", "VIEWER"));

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                .contains("Viewers cannot modify workspace variables")
                .contains("DO NOT RETRY");
        }

        @Test
        @DisplayName("REGRESSION (silent retype): set_variable OMITS the type key when the arg is absent - the backend preserves the stored type")
        void setVariableOmitsTypeWhenAbsent() throws Exception {
            // FLIPPED PIN: this used to assert $.type == "STRING" (the injected
            // default). That default was the retype bug: an agent rotating a
            // NUMBER/JSON variable's VALUE without re-passing type had the row
            // silently retyped to STRING by the backend. The key must be absent
            // so auth-service's preserve-on-omit contract keeps the stored type.
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andRespond(withSuccess("{\"id\":2,\"name\":\"retries\",\"type\":\"NUMBER\"}",
                    MediaType.APPLICATION_JSON));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "retries");
            args.put("value", "7");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("set_variable forwards an explicit type verbatim")
        void setVariableForwardsExplicitType() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andExpect(jsonPath("$.type").value("NUMBER"))
                .andRespond(withSuccess("{\"id\":2,\"name\":\"retries\",\"type\":\"NUMBER\"}",
                    MediaType.APPLICATION_JSON));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "retries");
            args.put("value", "3");
            args.put("type", "NUMBER");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("set_variable forwards secret=true in the payload when the arg is passed as a boolean")
        void setVariableForwardsSecretTrue() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andExpect(jsonPath("$.secret").value(true))
                .andRespond(withSuccess(
                    "{\"id\":3,\"name\":\"api_key\",\"value\":null,\"type\":\"STRING\",\"secret\":true}",
                    MediaType.APPLICATION_JSON));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "api_key");
            args.put("value", "sk-123");
            args.put("secret", Boolean.TRUE);

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("set_variable tolerates a stringified \"true\" for secret (LLMs serialize booleans differently)")
        void setVariableParsesStringifiedSecret() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andExpect(jsonPath("$.secret").value(true))
                .andRespond(withSuccess("{\"id\":3,\"name\":\"api_key\",\"secret\":true}",
                    MediaType.APPLICATION_JSON));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "api_key");
            args.put("value", "sk-123");
            args.put("secret", "true");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("set_variable omits the secret key entirely when the arg is absent - the backend default decides")
        void setVariableOmitsSecretWhenAbsent() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andRespond(withSuccess("{\"id\":4,\"name\":\"api_url\",\"secret\":false}",
                    MediaType.APPLICATION_JSON));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "api_url");
            args.put("value", "https://x");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("set_variable relays the 409 PLAN_RESOURCE_LIMIT_EXCEEDED message verbatim so the LLM sees the do-not-retry guidance")
        void setVariableRelaysPlanCap409Message() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            String conflictBody = """
                {"error":"PLAN_RESOURCE_LIMIT_EXCEEDED","resourceType":"WORKFLOW_VARIABLE",
                 "planCode":"FREE","currentCount":3,"limit":3,
                 "message":"LIMIT REACHED: Your FREE plan allows max 3 workflow variables (currently 3/3). Tell the user to upgrade their plan or delete an existing variable. DO NOT RETRY this operation."}
                """;
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(conflictBody));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "v4");
            args.put("value", "x");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                .contains("LIMIT REACHED")
                .contains("FREE")
                .contains("DO NOT RETRY");
        }

        @Test
        @DisplayName("set_variable relays a 400 validation message from auth-service (invalid_variable)")
        void setVariableRelays400ValidationMessage() throws Exception {
            MockRestServiceServer server = bindAuthServiceServer();
            server.expect(requestTo("http://localhost:8083/api/internal/variables/set?tenantId=tenant-1"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"invalid_variable\",\"message\":\"value is not a valid number\"}"));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("name", "retries");
            args.put("value", "abc");
            args.put("type", "NUMBER");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("value is not a valid number");
        }

        @Test
        @DisplayName("set_variable without name → error with a copy-pastable example, no HTTP call")
        void setVariableMissingNameFailsFast() {
            Map<String, Object> args = new HashMap<>();
            args.put("action", "set_variable");
            args.put("value", "x");

            ToolResult result = execute("credential", args, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                .contains("requires 'name' and 'value'")
                .contains("credential(action=\"set_variable\"");
        }

        @Test
        @DisplayName("set_variable with blank name or missing value → same fail-fast error")
        void setVariableBlankNameOrMissingValueFailsFast() {
            Map<String, Object> blankName = new HashMap<>();
            blankName.put("action", "set_variable");
            blankName.put("name", "   ");
            blankName.put("value", "x");
            assertThat(execute("credential", blankName, Map.of()).error())
                .contains("requires 'name' and 'value'");

            Map<String, Object> noValue = new HashMap<>();
            noValue.put("action", "set_variable");
            noValue.put("name", "api_url");
            assertThat(execute("credential", noValue, Map.of()).error())
                .contains("requires 'name' and 'value'");
        }
    }

    @Nested
    @DisplayName("isToolAvailable")
    class IsToolAvailableTests {

        @Test
        @DisplayName("should return true for set_conversation_title")
        void shouldReturnTrueForSetTitle() {
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            assertThat(service.isToolAvailable(toolDef, "tenant-1")).isTrue();
        }

        @Test
        @DisplayName("should return true for get_tool_result")
        void shouldReturnTrueForGetToolResult() {
            ToolDefinition toolDef = createToolDefinition("get_tool_result");
            assertThat(service.isToolAvailable(toolDef, "tenant-1")).isTrue();
        }

        @Test
        @DisplayName("should return true for the unified credential tool")
        void shouldReturnTrueForCredential() {
            ToolDefinition toolDef = createToolDefinition("credential");
            assertThat(service.isToolAvailable(toolDef, "tenant-1")).isTrue();
        }

        @Test
        @DisplayName("should return true for the legacy request_credential alias (pre-rename sessions)")
        void shouldReturnTrueForRequestCredential() {
            ToolDefinition toolDef = createToolDefinition("request_credential");
            assertThat(service.isToolAvailable(toolDef, "tenant-1")).isTrue();
        }

        @Test
        @DisplayName("should return true for core tools without apiSlug")
        void shouldReturnTrueForCoreTools() {
            ToolDefinition toolDef = ToolDefinition.builder()
                    .name("catalog")
                    .description("Catalog tool")
                    .build();
            assertThat(service.isToolAvailable(toolDef, "tenant-1")).isTrue();
        }
    }

    @Nested
    @DisplayName("General error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should handle exception during tool execution")
        void shouldHandleExecutionException() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title",
                    Map.of("title", "Test"));
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");
            Map<String, Object> credentials = Map.of("conversationId", "conv-1");

            doThrow(new RuntimeException("DB error"))
                    .when(conversationHistoryService).updateConversationTitle(any(), any(), any(), any());

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", credentials);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Failed to set title");
        }

        @Test
        @DisplayName("should handle null arguments gracefully")
        void shouldHandleNullArguments() {
            ToolCall toolCall = createToolCall("call_1", "set_conversation_title", null);
            ToolDefinition toolDef = createToolDefinition("set_conversation_title");

            ToolResult result = service.executeTool(toolCall, toolDef, "tenant-1", Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Title is required");
        }
    }
}
