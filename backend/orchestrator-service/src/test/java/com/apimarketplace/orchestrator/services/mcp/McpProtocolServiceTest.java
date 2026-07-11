package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared MCP protocol logic: tool-call wrapping and resource content.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpProtocolService")
class McpProtocolServiceTest {

    @Mock
    private AgentToolRegistry registry;

    @Mock
    private ToolsRegistrationService registrationService;

    private McpProtocolService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // No aggregation beans wired (monolith / local-only): the providers yield null.
        ObjectProvider<AggregatedToolCatalog> aggProvider = mock(ObjectProvider.class);
        ObjectProvider<RemoteToolGateway> gatewayProvider = mock(ObjectProvider.class);
        lenient().when(aggProvider.getIfAvailable()).thenReturn(null);
        lenient().when(gatewayProvider.getIfAvailable()).thenReturn(null);
        service = new McpProtocolService(registry, registrationService, new ObjectMapper(),
                aggProvider, gatewayProvider);
    }

    @Test
    @DisplayName("a successful tool result with structured data is serialized as JSON text content")
    void successfulStructuredResultIsSerializedAsJsonText() throws Exception {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), anyMap(), any()))
                .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("count", 2)));

        Map<String, Object> result = service.callTool("workflow", Map.of(), "42", null, null);

        assertThat(result.get("isError")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("type")).isEqualTo("text");
        assertThat((String) content.get(0).get("text")).contains("\"count\":2");
    }

    @Test
    @DisplayName("a failed tool result is reported in-band with isError true")
    void failedResultIsReportedInBand() throws Exception {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), anyMap(), any()))
                .thenReturn(new ToolsProvider.ToolExecutionResult(false, null, "workflow not found", null, Map.of()));

        Map<String, Object> result = service.callTool("workflow", Map.of(), "42", null, null);

        assertThat(result.get("isError")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("workflow not found");
    }

    @Test
    @DisplayName("a failed result without an error message falls back to a generic one")
    void failedResultWithoutMessageFallsBack() throws Exception {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), anyMap(), any()))
                .thenReturn(new ToolsProvider.ToolExecutionResult(false, null, null, null, Map.of()));

        Map<String, Object> result = service.callTool("workflow", Map.of(), "42", null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("Tool execution failed");
    }

    @Test
    @DisplayName("the execution context carries tenant and org scope, with no approved services")
    void executionContextCarriesTenantAndOrgScope() throws Exception {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), anyMap(), any()))
                .thenReturn(ToolsProvider.ToolExecutionResult.success("ok"));
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> captor =
                ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);

        service.callTool("workflow", Map.of("a", 1), "tenant-42", "org-1", "ADMIN");

        verify(registrationService).executeTool(eq("workflow"), eq(Map.of("a", 1)), captor.capture());
        ToolsProvider.ToolExecutionContext context = captor.getValue();
        assertThat(context.tenantId()).isEqualTo("tenant-42");
        assertThat(context.orgId()).isEqualTo("org-1");
        assertThat(context.orgRole()).isEqualTo("ADMIN");
        assertThat(context.approvedServices()).isEmpty();
    }

    // ==================== API-key scopes ====================

    private static final List<Map<String, Object>> THREE_TOOLS = List.of(
            Map.of("name", "agent", "description", "Manage agents"),
            Map.of("name", "table", "description", "Table CRUD"),
            Map.of("name", "workflow", "description", "Manage workflows"));

    @Test
    @DisplayName("listTools with null scopes returns the full tool list (full access)")
    void listToolsNullScopesReturnsAllTools() {
        when(registry.getToolsInMcpFormat()).thenReturn(THREE_TOOLS);

        assertThat(service.listTools(null))
                .extracting(t -> t.get("name")).containsExactly("agent", "table", "workflow");
    }

    @Test
    @DisplayName("listTools with scopes keeps only tools whose name is in the scope set")
    void listToolsWithScopesFiltersByName() {
        when(registry.getToolsInMcpFormat()).thenReturn(THREE_TOOLS);

        assertThat(service.listTools(Set.of("workflow", "table")))
                .extracting(t -> t.get("name")).containsExactly("table", "workflow");
    }

    @Test
    @DisplayName("listTools with an EMPTY scope set (zero-scope key) returns no tools")
    void listToolsWithEmptyScopesReturnsNothing() {
        when(registry.getToolsInMcpFormat()).thenReturn(THREE_TOOLS);

        assertThat(service.listTools(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("hasTool with scopes hides an existing but out-of-scope tool")
    void hasToolWithScopesHidesOutOfScopeTool() {
        lenient().when(registry.hasTool("workflow")).thenReturn(true);

        assertThat(service.hasTool("workflow", Set.of("table"))).isFalse();
        assertThat(service.hasTool("workflow", Set.of("workflow"))).isTrue();
        assertThat(service.hasTool("workflow", null)).isTrue();
    }

    @Test
    @DisplayName("scope matching is case-insensitive on the tool name")
    void hasToolScopeMatchIsCaseInsensitiveOnToolName() {
        when(registry.hasTool("Workflow")).thenReturn(true);

        assertThat(service.hasTool("Workflow", Set.of("workflow"))).isTrue();
    }

    @Test
    @DisplayName("callTool of an out-of-scope tool fails exactly like an unknown tool, without executing")
    void callToolOutOfScopeFailsLikeUnknownTool() throws Exception {
        Map<String, Object> result = service.callTool(
                "workflow", Map.of(), "42", null, null, Set.of("table"));

        assertThat(result.get("isError")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        // Same wording as a nonexistent tool: scoped keys cannot enumerate the catalog.
        assertThat(content.get(0).get("text")).isEqualTo("Unknown tool: workflow");
        verify(registrationService, never()).executeTool(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("callTool of an in-scope tool executes normally")
    void callToolInScopeExecutes() throws Exception {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), anyMap(), any()))
                .thenReturn(ToolsProvider.ToolExecutionResult.success("ok"));

        Map<String, Object> result = service.callTool(
                "workflow", Map.of(), "42", null, null, Set.of("workflow"));

        assertThat(result.get("isError")).isEqualTo(false);
    }

    // ==================== resources ====================

    @Test
    @DisplayName("an unknown resource uri yields null content")
    void unknownResourceUriYieldsNull() {
        assertThat(service.getResourceContent("schema://nope")).isNull();
    }

    @Test
    @DisplayName("the resource catalog lists the four schemas and the tools documentation")
    void resourceCatalogListsSchemasAndDocs() {
        List<Map<String, Object>> resources = service.listResources();

        assertThat(resources).extracting(r -> r.get("uri")).containsExactly(
                "schema://workflow", "schema://agent", "schema://interface",
                "schema://datasource", "docs://tools");
    }

    @Test
    @DisplayName("resource mime type is JSON for schemas and markdown otherwise")
    void resourceMimeTypeFollowsUriScheme() {
        assertThat(service.resourceMimeType("schema://workflow")).isEqualTo("application/json");
        assertThat(service.resourceMimeType("docs://tools")).isEqualTo("text/markdown");
    }
}
