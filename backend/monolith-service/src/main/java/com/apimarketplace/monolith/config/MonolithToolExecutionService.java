package com.apimarketplace.monolith.config;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.prompt.ConversationToolDefinitions;
import com.apimarketplace.agent.tool.ToolExecutionService;

import java.util.Map;
import java.util.Set;

/**
 * Monolith ToolExecutionService adapter.
 * Intercepts conversation-local tools and handles them in-process. All other tools
 * are delegated to RemoteToolExecutionService.
 *
 * This avoids the dual-@Primary conflict and eliminates the HTTP callback loop.
 */
public class MonolithToolExecutionService implements ToolExecutionService {

    private static final Set<String> CONVERSATION_LOCAL_TOOLS =
            ConversationToolDefinitions.ALL_CONVERSATION_TOOL_NAMES;

    private final ToolExecutionService delegate;
    private final ToolExecutionService conversationLocalToolExecutionService;

    public MonolithToolExecutionService(ToolExecutionService delegate,
                                        ToolExecutionService conversationLocalToolExecutionService) {
        this.delegate = delegate;
        this.conversationLocalToolExecutionService = conversationLocalToolExecutionService;
    }

    @Override
    public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                  String tenantId, Map<String, Object> credentials) {
        if (CONVERSATION_LOCAL_TOOLS.contains(toolCall.toolName())) {
            return conversationLocalToolExecutionService.executeTool(toolCall, toolDefinition, tenantId, credentials);
        }
        return delegate.executeTool(toolCall, toolDefinition, tenantId, credentials);
    }

    @Override
    public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
        return delegate.isToolAvailable(toolDefinition, tenantId);
    }
}
