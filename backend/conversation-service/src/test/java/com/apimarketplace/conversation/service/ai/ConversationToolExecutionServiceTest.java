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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @Nested
    @DisplayName("request_credential")
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
        @DisplayName("should return true for request_credential")
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
