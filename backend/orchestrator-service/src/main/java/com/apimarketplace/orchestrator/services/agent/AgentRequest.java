package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolDefinition;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Request for agent execution.
 */
@Builder
public record AgentRequest(
    /**
     * The user's prompt/question
     */
    String prompt,
    
    /**
     * System prompt for the agent
     */
    String systemPrompt,
    
    /**
     * Provider name (openai, anthropic, google, mistral, deepseek)
     */
    String provider,
    
    /**
     * Model name (e.g., gpt-4, claude-3-sonnet, gemini-pro)
     */
    String model,
    
    /**
     * Temperature for generation
     */
    Double temperature,
    
    /**
     * Maximum tokens to generate
     */
    Integer maxTokens,
    
    /**
     * Explicit tools to provide to the agent.
     * <ul>
     *   <li>{@code null} - Auto-discover tools based on prompt (default behavior)</li>
     *   <li>{@code []} (empty list) - No tools available (agent cannot call tools)</li>
     *   <li>{@code [tool1, tool2]} - Use these specific tools only</li>
     * </ul>
     */
    List<ToolDefinition> tools,

    /**
     * Whether to auto-discover tools based on the prompt.
     * Only used when {@code tools} is {@code null}.
     * <ul>
     *   <li>{@code null} or {@code true} - Enable auto-discovery (default)</li>
     *   <li>{@code false} - Disable auto-discovery (no tools available)</li>
     * </ul>
     */
    Boolean autoDiscoverTools,

    /**
     * Canonical enabled MODULE keys (AgentModuleResolver vocabulary: catalog, table,
     * interface, agent, skill, workflow, application, web_search, files, image_generation),
     * resolved from the agent entity's {@code toolsConfig}. When non-null, agent-service
     * scopes the auto-discovered core tool SCHEMAS to these modules (parity with the chat
     * path); {@code null} ⇒ unrestricted (all core tools), the legacy behaviour preserved
     * for agents with no toolsConfig.
     */
    List<String> enabledModules,

    /**
     * Maximum number of tools to discover
     */
    Integer maxTools,
    
    /**
     * Maximum number of tool call iterations
     */
    Integer maxIterations,

    /**
     * Maximum execution time in seconds (10-7200). Checked between iterations.
     */
    Integer executionTimeout,

    /**
     * Conversation history for multi-turn
     */
    List<Message> conversationHistory,
    
    /**
     * Tenant ID for multi-tenancy
     */
    String tenantId,
    
    /**
     * Workflow run ID (if in workflow context)
     */
    String runId,
    
    /**
     * Node ID (if in workflow context)
     */
    String nodeId,
    
    /**
     * Variables for expression resolution
     */
    Map<String, Object> variables,
    
    /**
     * Credentials for tool execution
     */
    Map<String, Object> credentials
) {}
