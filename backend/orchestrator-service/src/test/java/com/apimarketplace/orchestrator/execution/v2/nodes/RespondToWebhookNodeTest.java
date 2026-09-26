package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.webhook.WebhookResponseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RespondToWebhookNode.
 * RespondToWebhookNode controls the HTTP response returned to webhook callers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RespondToWebhookNode")
class RespondToWebhookNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private WebhookResponseRegistry webhookResponseRegistry;

    @Mock
    private V2TemplateAdapter templateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "test-webhook");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create node with nodeId and config")
        void shouldCreateNodeWithNodeIdAndConfig() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "{\"ok\":true}", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);

            assertEquals("core:respond", node.getNodeId());
            assertEquals(NodeType.RESPOND_TO_WEBHOOK, node.getType());
            assertNotNull(node.getRespondToWebhookConfig());
            assertEquals(200, node.getRespondToWebhookConfig().statusCode());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", null);

            assertEquals("core:respond", node.getNodeId());
            assertNull(node.getRespondToWebhookConfig());
        }

        @Test
        @DisplayName("Should default config values properly")
        void shouldDefaultConfigValuesProperly() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(0, null, null, null);

            assertEquals(200, config.statusCode());
            assertEquals("application/json", config.contentType());
            assertEquals(Map.of(), config.headers());
        }

        @Test
        @DisplayName("Should create node using builder pattern")
        void shouldCreateNodeUsingBuilderPattern() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(201, "{\"created\":true}", "application/json", Map.of());

            RespondToWebhookNode node = RespondToWebhookNode.builder()
                .nodeId("core:my_respond")
                .respondToWebhookConfig(config)
                .build();

            assertEquals("core:my_respond", node.getNodeId());
            assertEquals(NodeType.RESPOND_TO_WEBHOOK, node.getType());
            assertEquals(201, node.getRespondToWebhookConfig().statusCode());
        }
    }

    // ===============================================================
    // execute() - Webhook response exists
    // ===============================================================

    @Nested
    @DisplayName("execute() - Webhook response exists")
    class ExecuteWebhookExistsTests {

        @Test
        @DisplayName("Should resolve deferred result and return responded=true")
        void shouldResolveDeferredResultAndReturnRespondedTrue() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "{\"status\":\"ok\"}", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(true, result.output().get("responded"));
            assertEquals(200, result.output().get("statusCode"));
            assertEquals("application/json", result.output().get("contentType"));

            verify(webhookResponseRegistry).resolve(eq("run-1"), any(ResponseEntity.class));
        }

        @Test
        @DisplayName("Should pass correct ResponseEntity to registry")
        void shouldPassCorrectResponseEntityToRegistry() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(201, "Created!", "text/plain", Map.of("X-Custom", "value"));
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

            node.execute(context);

            ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
            verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());

            ResponseEntity<?> captured = captor.getValue();
            assertEquals(201, captured.getStatusCode().value());
            assertEquals("Created!", captured.getBody());
            assertEquals("text/plain", captured.getHeaders().getFirst("Content-Type"));
            assertEquals("value", captured.getHeaders().getFirst("X-Custom"));
        }

        @Test
        @DisplayName("Should resolve body expression using template adapter")
        void shouldResolveBodyExpressionUsingTemplateAdapter() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "{{mcp:step1.output.data}}", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);
            node.setTemplateAdapter(templateAdapter);

            when(templateAdapter.resolveTemplates(any(), eq(context))).thenAnswer(TemplateResolutionStubs.every("{\"resolved\":\"data\"}"));
            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            verify(templateAdapter).resolveTemplates(any(), eq(context));

            ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
            verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());
            assertEquals("{\"resolved\":\"data\"}", captor.getValue().getBody());
        }
    }

    // ===============================================================
    // execute() - No pending webhook
    // ===============================================================

    @Nested
    @DisplayName("execute() - No pending webhook")
    class ExecuteNoPendingWebhookTests {

        @Test
        @DisplayName("Should succeed with responded=false when no pending webhook")
        void shouldSucceedWithRespondedFalseWhenNoPendingWebhook() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "{\"ok\":true}", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(false);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(false, result.output().get("responded"));
            assertEquals(200, result.output().get("statusCode"));
        }

        @Test
        @DisplayName("Should succeed when webhookResponseRegistry is null")
        void shouldSucceedWhenWebhookResponseRegistryIsNull() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "body", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            // Do not set webhookResponseRegistry

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(false, result.output().get("responded"));
        }
    }

    // ===============================================================
    // execute() - Custom status code, headers, content type
    // ===============================================================

    @Nested
    @DisplayName("execute() - Custom configurations")
    class ExecuteCustomConfigTests {

        @Test
        @DisplayName("Should use custom status code 404")
        void shouldUseCustomStatusCode404() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(404, "{\"error\":\"not found\"}", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(404, result.output().get("statusCode"));

            ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
            verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());
            assertEquals(404, captor.getValue().getStatusCode().value());
        }

        @Test
        @DisplayName("Should use custom content type text/html")
        void shouldUseCustomContentTypeTextHtml() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "<h1>Hello</h1>", "text/html", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

            NodeExecutionResult result = node.execute(context);

            assertEquals("text/html", result.output().get("contentType"));

            ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
            verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());
            assertEquals("text/html", captor.getValue().getHeaders().getFirst("Content-Type"));
        }

        @Test
        @DisplayName("Should include custom headers in response")
        void shouldIncludeCustomHeadersInResponse() {
            Map<String, String> customHeaders = Map.of("X-Request-Id", "abc-123", "X-Rate-Limit", "100");
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "body", "application/json", customHeaders);
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

            node.execute(context);

            ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
            verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());
            assertEquals("abc-123", captor.getValue().getHeaders().getFirst("X-Request-Id"));
            assertEquals("100", captor.getValue().getHeaders().getFirst("X-Rate-Limit"));
        }

        @Test
        @DisplayName("Should handle null body expression")
        void shouldHandleNullBodyExpression() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(204, null, "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(204, result.output().get("statusCode"));

            ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
            verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());
            assertNull(captor.getValue().getBody());
        }

        @Test
        @DisplayName("Should use defaults when config is null")
        void shouldUseDefaultsWhenConfigIsNull() {
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", null);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(false);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(200, result.output().get("statusCode"));
            assertEquals("application/json", result.output().get("contentType"));
        }
    }

    // ===============================================================
    // execute() - Metadata tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Metadata")
    class ExecuteMetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields")
        void shouldIncludeMandatoryMetadataFields() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "body", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(false);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("RESPOND_TO_WEBHOOK", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include resolved_params with config values")
        void shouldIncludeInputDataWithConfigValues() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(201, "{{expr}}", "text/plain", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            node.setWebhookResponseRegistry(webhookResponseRegistry);

            when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(false);

            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            Map<String, Object> inputData = (Map<String, Object>) result.output().get("resolved_params");
            assertNotNull(inputData);
            assertEquals(201, inputData.get("statusCode"));
            assertEquals("text/plain", inputData.get("contentType"));
            assertEquals("{{expr}}", inputData.get("body"));
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "ok", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:respond", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "ok", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:respond", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // onComplete() tests
    // ===============================================================

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "ok", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            NodeExecutionResult result = NodeExecutionResult.success("core:respond", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "ok", "application/json", Map.of());
            RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
            NodeExecutionResult result = NodeExecutionResult.failure("core:respond", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }

    @Test
    @DisplayName("reports the configured headers, which the node sent on the wire but never showed the reader")
    @SuppressWarnings("unchecked")
    void reportsConfiguredHeaders() {
        Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(
            201, "{\"ok\":true}", "application/json", Map.of("X-Trace", "abc123"));
        RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);

        NodeExecutionResult result = node.execute(context);

        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        // RespondToWebhookConfig has `headers` and the node applied them to the
        // response, but never reported them - while input-label-registry already
        // declared a `headers` label, waiting on a key that never came.
        assertEquals(Map.of("X-Trace", "abc123"), params.get("headers"));
        assertEquals(201, params.get("statusCode"));
        assertEquals("application/json", params.get("contentType"));
    }

    @Test
    @DisplayName("omits headers entirely when none are configured, rather than showing an empty map")
    @SuppressWarnings("unchecked")
    void omitsHeadersWhenNoneConfigured() {
        Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(
            200, "{}", "application/json", Map.of());
        RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);

        NodeExecutionResult result = node.execute(context);

        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        // An empty map in the column reads as "I configured headers", which is the
        // same lie as a null-valued key.
        assertFalse(params.containsKey("headers"));
    }

    @Test
    @DisplayName("resolves a header VALUE reference; it used to be sent as the literal {{...}} while the body resolved")
    @SuppressWarnings("unchecked")
    void resolvesHeaderValueReferences() {
        Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(
            200, "{{core:build.output.body}}", "application/json",
            Map.of("X-Request-Id", "{{core:build.output.request_id}}"));
        RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
        node.setWebhookResponseRegistry(webhookResponseRegistry);
        node.setTemplateAdapter(templateAdapter);
        Map<String, Object> values = new HashMap<>();
        values.put("{{core:build.output.body}}", Map.of("ok", true));
        values.put("{{core:build.output.request_id}}", "req-42");
        when(templateAdapter.resolveTemplates(any(), any())).thenAnswer(TemplateResolutionStubs.resolving(values));
        when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
        verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());
        assertEquals("req-42", captor.getValue().getHeaders().getFirst("X-Request-Id"));
        // A whole-reference body that is an object is sent as its JSON, never as Java's {ok=true}.
        assertEquals("{\"ok\":true}", captor.getValue().getBody());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals(Map.of("X-Request-Id", "req-42"), params.get("headers"));
    }

    @Test
    @DisplayName("a header value pulled from a workspace variable is sent, but withheld in Params")
    @SuppressWarnings("unchecked")
    void workspaceVariableHeaderValueIsWithheld() {
        // "X-Tier" names nothing the credential word rules can read.
        Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(
            200, "ok", "text/plain", Map.of("X-Tier", "{{$vars.tier_secret}}"));
        RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
        node.setWebhookResponseRegistry(webhookResponseRegistry);
        node.setTemplateAdapter(templateAdapter);
        when(templateAdapter.resolveTemplates(any(), any()))
            .thenAnswer(TemplateResolutionStubs.resolving(Map.of("{{$vars.tier_secret}}", "s3cr3t")));
        when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

        NodeExecutionResult result = node.execute(context);

        ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
        verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());
        assertEquals("s3cr3t", captor.getValue().getHeaders().getFirst("X-Tier"));
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals(Map.of("X-Tier", com.apimarketplace.orchestrator.services.template.ReportedParams.WITHHELD_WORKSPACE_VARIABLE),
            params.get("headers"));
    }

    @Test
    @DisplayName("regression: a {{...}} statusCode is resolved and sent; it used to drop the whole respondToWebhook config")
    @SuppressWarnings("unchecked")
    void templatedStatusCodeIsResolved() {
        Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "ok", "text/plain", Map.of());
        RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
        node.setWebhookResponseRegistry(webhookResponseRegistry);
        node.setTemplateAdapter(templateAdapter);
        node.setDeferredScalars(Map.of("respondToWebhook", Map.of("statusCode", "{{core:x.output.code}}")));
        when(templateAdapter.resolveTemplates(any(), any()))
            .thenAnswer(TemplateResolutionStubs.resolving(Map.of("{{core:x.output.code}}", 201)));
        when(webhookResponseRegistry.resolve(eq("run-1"), any(ResponseEntity.class))).thenReturn(true);

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess(), String.valueOf(result.errorMessage()));
        ArgumentCaptor<ResponseEntity<?>> captor = ArgumentCaptor.forClass(ResponseEntity.class);
        verify(webhookResponseRegistry).resolve(eq("run-1"), captor.capture());
        assertEquals(201, captor.getValue().getStatusCode().value());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals(201, params.get("statusCode"));
    }

    @Test
    @DisplayName("a {{...}} statusCode resolving to a non-number fails, naming the config")
    void templatedStatusCodeNotANumberFails() {
        Core.RespondToWebhookConfig config = new Core.RespondToWebhookConfig(200, "ok", "text/plain", Map.of());
        RespondToWebhookNode node = new RespondToWebhookNode("core:respond", config);
        node.setWebhookResponseRegistry(webhookResponseRegistry);
        node.setTemplateAdapter(templateAdapter);
        node.setDeferredScalars(Map.of("respondToWebhook", Map.of("statusCode", "{{core:x.output.code}}")));
        when(templateAdapter.resolveTemplates(any(), any()))
            .thenAnswer(TemplateResolutionStubs.resolving(Map.of("{{core:x.output.code}}", "created")));

        NodeExecutionResult result = node.execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("respondToWebhook"), result.errorMessage().orElse(""));
    }
}
