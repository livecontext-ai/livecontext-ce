package com.apimarketplace.agent.service.cli;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.dto.cli.CliSessionResponse;
import com.apimarketplace.agent.dto.cli.CliSessionStartRequest;
import com.apimarketplace.agent.dto.cli.CliToolRequest;
import com.apimarketplace.agent.dto.cli.CliToolResponse;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.execution.CoreToolsCache;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LC-004: every tool call of a CLI-bridge session goes back to a CLI running on a subscription
 * account, which is not allowed to receive Google restricted-scope data. A Gmail / Drive result is
 * therefore withheld and replaced by the refusal.
 */
@DisplayName("CliAgentService restricted-data guard")
class CliAgentServiceRestrictedDataTest {

    private static final String SECRET = "Hi Alice, the wire transfer of 45000 EUR is approved";

    private CliToolResponse runTool(Map<String, Object> resultMetadata) {
        CoreToolsCache coreToolsCache = mock(CoreToolsCache.class);
        when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of());
        ToolExecutionService stub = new ToolExecutionService() {
            @Override
            public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                          String tenantId, Map<String, Object> credentials) {
                return ToolResult.builder().toolCall(toolCall).success(true)
                        .content("{\"body\":\"" + SECRET + "\"}").metadata(resultMetadata).build();
            }

            @Override
            public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
                return true;
            }
        };
        CliAgentService service = new CliAgentService(coreToolsCache, stub,
                mock(AgentObservabilityService.class), mock(AgentService.class), new ObjectMapper());
        // conversationId present: the session advertises the conversation tools, one of which
        // stands in for any catalog call here (the guard reads only the result metadata).
        CliSessionResponse session = service.startSession(
                new CliSessionStartRequest(null, null, "claude-code", "conv-1",
                        null, null, Boolean.TRUE, null, null, null),
                "tenant-1", "org-test");
        return service.executeTool(
                new CliToolRequest(session.sessionId(), "credential", Map.of("action", "list")), "tenant-1");
    }

    @Test
    @DisplayName("a Gmail result never reaches the CLI: the call fails with the refusal")
    void gmailResultWithheld() {
        CliToolResponse response = runTool(Map.of("iconSlug", "gmail"));

        assertThat(response.success()).isFalse();
        assertThat(response.result()).isNull();
        assertThat(response.error()).contains("Gmail").doesNotContain(SECRET);
        assertThat(response.metadata()).isNull();
    }

    @Test
    @DisplayName("self-hosted (allow-list not enforced): the operator's own CLI receives the Gmail result")
    void ceModeRelaysRestrictedResult() {
        com.apimarketplace.common.classification.RestrictedDataPolicy.setLlmAllowListEnforced(false);
        try {
            CliToolResponse response = runTool(Map.of("iconSlug", "gmail"));

            assertThat(response.success()).isTrue();
            assertThat(response.result()).contains(SECRET);
        } finally {
            com.apimarketplace.common.classification.RestrictedDataPolicy.setLlmAllowListEnforced(true);
        }
    }

    @Test
    @DisplayName("an ordinary result is relayed unchanged")
    void ordinaryResultRelayed() {
        CliToolResponse response = runTool(Map.of("iconSlug", "slack"));

        assertThat(response.success()).isTrue();
        assertThat(response.result()).contains(SECRET);
    }
}
