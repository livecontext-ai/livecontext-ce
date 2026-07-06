package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cloud MCP surface must expose the SAME tools as the CE monolith: the local
 * registry UNION the sibling-service tools discovered by {@link AggregatedToolCatalog},
 * with calls to remote tools routed through {@link RemoteToolGateway}. In the
 * monolith the aggregation beans are absent and behaviour falls back to local-only.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpProtocolService - cloud tool aggregation")
class McpProtocolServiceAggregationTest {

    @Mock
    private AgentToolRegistry registry;
    @Mock
    private ToolsRegistrationService registrationService;
    @Mock
    private AggregatedToolCatalog aggregatedCatalog;
    @Mock
    private RemoteToolGateway remoteGateway;

    @SuppressWarnings("unchecked")
    private McpProtocolService withAggregation() {
        ObjectProvider<AggregatedToolCatalog> aggProvider = mock(ObjectProvider.class);
        ObjectProvider<RemoteToolGateway> gatewayProvider = mock(ObjectProvider.class);
        lenient().when(aggProvider.getIfAvailable()).thenReturn(aggregatedCatalog);
        lenient().when(gatewayProvider.getIfAvailable()).thenReturn(remoteGateway);
        return new McpProtocolService(registry, registrationService, new ObjectMapper(),
                aggProvider, gatewayProvider);
    }

    @SuppressWarnings("unchecked")
    private McpProtocolService monolith() {
        ObjectProvider<AggregatedToolCatalog> aggProvider = mock(ObjectProvider.class);
        ObjectProvider<RemoteToolGateway> gatewayProvider = mock(ObjectProvider.class);
        lenient().when(aggProvider.getIfAvailable()).thenReturn(null);
        lenient().when(gatewayProvider.getIfAvailable()).thenReturn(null);
        return new McpProtocolService(registry, registrationService, new ObjectMapper(),
                aggProvider, gatewayProvider);
    }

    private static Map<String, Object> mcpTool(String name) {
        return Map.of("name", name, "description", name + " desc", "inputSchema", Map.of());
    }

    @Test
    @DisplayName("listTools merges local and aggregated tools, name-sorted")
    void listToolsMergesLocalAndAggregated() {
        when(registry.getToolsInMcpFormat()).thenReturn(List.of(mcpTool("workflow"), mcpTool("agent_browse")));
        when(aggregatedCatalog.mcpTools()).thenReturn(List.of(mcpTool("table"), mcpTool("catalog")));

        List<Map<String, Object>> tools = withAggregation().listTools();

        assertThat(tools).extracting(t -> t.get("name"))
                .containsExactly("agent_browse", "catalog", "table", "workflow");
    }

    @Test
    @DisplayName("listTools keeps the LOCAL definition when a name exists in both")
    void listToolsLocalWinsOnClash() {
        Map<String, Object> local = Map.of("name", "catalog", "description", "LOCAL", "inputSchema", Map.of());
        Map<String, Object> remote = Map.of("name", "catalog", "description", "REMOTE", "inputSchema", Map.of());
        when(registry.getToolsInMcpFormat()).thenReturn(List.of(local));
        when(aggregatedCatalog.mcpTools()).thenReturn(List.of(remote));

        List<Map<String, Object>> tools = withAggregation().listTools();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("description")).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("hasTool is true for a remote-only tool when aggregation is wired")
    void hasToolTrueForRemoteTool() {
        when(registry.hasTool("table")).thenReturn(false);
        when(aggregatedCatalog.knows("table")).thenReturn(true);

        assertThat(withAggregation().hasTool("table")).isTrue();
    }

    @Test
    @DisplayName("callTool routes a remote tool through the gateway and wraps its success")
    void callToolRoutesRemoteToolThroughGateway() throws Exception {
        when(registry.hasTool("table")).thenReturn(false);
        when(remoteGateway.execute(eq("table"), anyMap(), eq("tenant-1"), eq("org-1"), eq("ADMIN")))
                .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("rows", 3)));

        Map<String, Object> result = withAggregation()
                .callTool("table", Map.of("action", "find_rows"), "tenant-1", "org-1", "ADMIN");

        assertThat(result.get("isError")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat((String) content.get(0).get("text")).contains("\"rows\":3");
        // A remote tool must NOT touch the local registration path.
        verify(registrationService, never()).executeTool(any(), anyMap(), any());
    }

    @Test
    @DisplayName("callTool reports a remote tool failure in-band with isError true")
    void callToolReportsRemoteFailure() throws Exception {
        when(registry.hasTool("table")).thenReturn(false);
        when(remoteGateway.execute(eq("table"), anyMap(), any(), any(), any()))
                .thenReturn(ToolsProvider.ToolExecutionResult.failure(
                        com.apimarketplace.agent.tools.ToolErrorCode.EXECUTION_FAILED, "datasource down"));

        Map<String, Object> result = withAggregation().callTool("table", Map.of(), "t1", null, null);

        assertThat(result.get("isError")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("datasource down");
    }

    @Test
    @DisplayName("callTool prefers the LOCAL path when the tool is in the local registry")
    void callToolPrefersLocalWhenRegistered() throws Exception {
        when(registry.hasTool("workflow")).thenReturn(true);
        when(registrationService.executeTool(eq("workflow"), anyMap(), any()))
                .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));

        withAggregation().callTool("workflow", Map.of(), "tenant-1", null, null);

        verify(registrationService).executeTool(eq("workflow"), anyMap(), any());
        verify(remoteGateway, never()).execute(any(), anyMap(), any(), any(), any());
    }

    @Test
    @DisplayName("monolith (no aggregation beans): listTools returns only the local registry")
    void monolithListToolsLocalOnly() {
        when(registry.getToolsInMcpFormat()).thenReturn(List.of(mcpTool("workflow")));

        List<Map<String, Object>> tools = monolith().listTools();

        assertThat(tools).extracting(t -> t.get("name")).containsExactly("workflow");
    }

    @Test
    @DisplayName("monolith: an unknown tool call fails cleanly with TOOL_NOT_FOUND, no NPE")
    void monolithUnknownToolFailsCleanly() throws Exception {
        when(registry.hasTool("nope")).thenReturn(false);

        Map<String, Object> result = monolith().callTool("nope", Map.of(), "tenant-1", null, null);

        assertThat(result.get("isError")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat((String) content.get(0).get("text")).contains("Unknown tool: nope");
    }
}
