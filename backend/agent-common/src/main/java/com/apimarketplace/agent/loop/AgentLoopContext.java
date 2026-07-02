package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.MessageAttachment;
import com.apimarketplace.agent.domain.SystemBlock;
import com.apimarketplace.agent.domain.ThinkingLevel;
import com.apimarketplace.agent.domain.ToolDefinition;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Context for agent loop execution.
 * Contains all parameters needed to execute an agent.
 */
@Builder
public record AgentLoopContext(
    /**
     * The LLM provider to use (openai, anthropic, google, mistral, deepseek)
     */
    String provider,

    /**
     * The model to use (provider-specific)
     */
    String model,

    /**
     * System prompt for the agent
     */
    String systemPrompt,

    /**
     * The current user message
     */
    String userPrompt,

    /**
     * Attachments for the current user message (images, PDFs, text files)
     */
    List<MessageAttachment> currentMessageAttachments,

    /**
     * Previous conversation messages
     */
    List<Message> conversationHistory,

    /**
     * Tools available to the agent
     */
    List<ToolDefinition> tools,

    /**
     * Whether to auto-discover tools based on the prompt
     */
    Boolean autoDiscoverTools,

    /**
     * Maximum number of tools to auto-discover
     */
    Integer maxTools,

    /**
     * Maximum iterations of the agent loop
     */
    Integer maxIterations,

    /**
     * Maximum execution time in seconds for the agent (10-7200). Checked between iterations.
     */
    Integer executionTimeout,

    /**
     * Inactivity watchdog window in seconds. If the agent emits NO activity (content token,
     * thinking token, tool call, or tool result) for this long, it is force-stopped with
     * {@link com.apimarketplace.agent.domain.AgentStopReason#INACTIVITY_TIMEOUT}. Distinct from
     * {@code executionTimeout} (total runtime): a working agent that keeps streaming resets the
     * idle clock and is never stopped by it - only a stalled/hung agent is. {@code null} => the
     * platform default (see {@link #getInactivityTimeoutMsOrDefault()}); {@code <= 0} disables it.
     */
    Integer inactivityTimeout,

    /**
     * Maximum tokens in the response
     */
    Integer maxTokens,

    /**
     * Temperature for generation
     */
    Double temperature,

    /**
     * Top-p sampling parameter
     */
    Double topP,

    /**
     * Tenant ID for authorization
     */
    String tenantId,

    /**
     * Comma-separated user roles (mirrors the {@code X-User-Roles} header format).
     * Nullable - a {@code null} or blank value is treated as {@code USER} by
     * downstream guards (e.g. the CLI-bridge access guard's admin_only check).
     *
     * <p>Added for bridge access control: some bridges (claude-code, codex, …)
     * are configured {@code admin_only} and need the role to grant dispatch.
     * Non-bridge providers ignore this field entirely.
     */
    String userRoles,

    /**
     * Agent entity ID - propagated to {@link IterationContext} so per-iteration guards
     * can scope decisions to a specific agent. Optional: {@code null} for sub-agent
     * runs that don't have a backing entity.
     */
    String agentId,

    /**
     * Run ID (for workflow context)
     */
    String runId,

    /**
     * Stable per-execution UUID minted by the dispatcher. Becomes
     * {@code agent_executions.id} and is threaded into MCP credentials as
     * {@code __executionId__} so {@code task_claim} writes the claim log row
     * keyed by this id - closing the race where the claim happens before the
     * agent_executions row exists. Distinct from {@link #runId} which is the
     * workflow-level id (workflow_runs.id) when called from a workflow node.
     */
    String executionId,

    /**
     * Node ID (for workflow context)
     */
    String nodeId,

    /**
     * Credentials for tool execution
     */
    Map<String, Object> credentials,

    /**
     * Variables available for interpolation
     */
    Map<String, Object> variables,

    /**
     * Set of approved external services for this conversation.
     * Services like "gmail", "slack", etc. that the user has approved.
     * Used by tools to check if they can execute without requesting approval.
     */
    Set<String> approvedServices,

    /**
     * Optional guard checked before each loop iteration.
     * Returns false to stop the loop early (e.g., credit budget exhausted).
     * Defaults to ALWAYS_PROCEED if null.
     */
    PreIterationGuard preIterationGuard,

    /**
     * Per-agent override of the LoopDetector identical-call hard-stop threshold
     * (see {@code AgentEntity.loopIdenticalStop}). {@code null} = use platform
     * default from {@code AgentDefaultsConfig}.
     */
    Integer loopIdenticalStop,

    /**
     * Per-agent override of the LoopDetector consecutive-call hard-stop threshold
     * (see {@code AgentEntity.loopConsecutiveStop}). {@code null} = use platform
     * default from {@code AgentDefaultsConfig}.
     */
    Integer loopConsecutiveStop,

    /**
     * Classifies the LLM call for the centralized context-optimization pipeline.
     * {@link CallPurpose#MAIN} routes through the full pipeline (zones, cache,
     * skills snapshot, compaction, summarizer). {@link CallPurpose#CLASSIFY}
     * and {@link CallPurpose#GUARDRAIL} bypass to a fast path.
     *
     * <p>{@code null} is treated as MAIN (conservative default - new callers
     * route through the pipeline unless they explicitly opt out).
     */
    CallPurpose purpose,

    /**
     * Stage 1a.1 - layered system prompt as an ordered list of slices so the
     * Claude provider can emit prompt-cache breakpoints at discrete positions.
     * Callers that want caching build the list via {@code SystemBlock.of(...)}
     * / {@code SystemBlock.breakpoint(...)}; the loop threads this list into
     * {@code CompletionRequest.systemBlocks} at the single chokepoint
     * ({@code AgentLoopExecutor#buildCompletionRequest}). Non-Claude providers
     * concatenate block text and ignore the breakpoint flags.
     *
     * <p>Precedence with {@link #systemPrompt}: when {@code systemBlocks} is
     * present Claude uses the native array path; non-Claude providers still
     * get a usable string via {@code CompletionRequest#effectiveSystemPrompt()}.
     * Callers may set only {@code systemBlocks}; the loop falls back to the
     * legacy {@code systemPrompt} string only when {@code systemBlocks} is
     * empty/null, so existing call sites that still populate {@code systemPrompt}
     * keep working without change.
     */
    List<SystemBlock> systemBlocks,

    /**
     * Stage 1b.1 - caller-pinned {@link ThinkingLevel} override. When non-null,
     * {@code AgentLoopExecutor.resolveAdaptiveThinkingLevel} skips auto-resolution
     * and propagates this tier unchanged to every iteration's
     * {@code CompletionRequest}. When {@code null} the loop auto-resolves
     * per-iteration from {@link #purpose}, tool count, and user-message length.
     *
     * <p>Used primarily by the Claude path: Anthropic's prompt cache is
     * invalidated when the {@code thinking} parameter flips across turns
     * (system + messages segments), so a conversation-scoped caller pins the
     * first MAIN turn's resolved level and passes it here on every subsequent
     * turn. Gemini and OpenAI callers leave this {@code null} and let the
     * loop's per-iteration resolver pick the tier - Gemini's
     * {@code thinkingConfig} sits in {@code generationConfig} and does not
     * invalidate {@code cachedContent}, so flipping is safe there.
     */
    ThinkingLevel thinkingLevel,

    /**
     * Resolved reasoning-effort level: canonical lowercase
     * {@code minimal|low|medium|high|xhigh|max}, or {@code null} to inherit
     * the provider's own default. Resolved by {@code ReasoningEffortResolver}
     * (per-conversation override > per-agent > per-model default) at
     * context-build time. Carried verbatim into the bridge request DTO for
     * CLI providers (claude-code, codex), and forwarded on the direct-API
     * {@code CompletionRequest} where {@code ClaudeProvider} maps it to
     * Anthropic {@code output_config.effort} on supporting models. Other
     * direct providers ignore it.
     */
    String reasoningEffort,

    /**
     * Canonical enabled MODULE keys (AgentModuleResolver vocabulary) resolved from the agent's
     * toolsConfig. Carried so the chat/task bridge dispatch ({@code ConversationAgentService})
     * can forward them to the MCP subprocess and scope its core tool set (parity with the
     * direct loop). {@code null} ⇒ unrestricted (all modules). Not used by the direct loop,
     * which scopes tools before building this context.
     */
    List<String> enabledModules
) {
    /**
     * Get max iterations with default
     */
    public int getMaxIterationsOrDefault() {
        return maxIterations != null ? maxIterations : 10;
    }

    /**
     * Get max tools with default
     */
    public int getMaxToolsOrDefault() {
        return maxTools != null ? maxTools : 5;
    }

    /**
     * Get execution timeout in milliseconds, or 0 if no timeout is set.
     */
    public long getExecutionTimeoutMsOrZero() {
        return executionTimeout != null && executionTimeout > 0
            ? executionTimeout * 1000L : 0L;
    }

    /** Platform default inactivity window (5 min) applied when no per-agent value is set. */
    public static final long DEFAULT_INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L;

    /**
     * Inactivity watchdog window in milliseconds. {@code null} => the platform default (5 min, so
     * every agent gets a heartbeat by default); an explicit positive value (seconds) is used as-is;
     * {@code <= 0} disables the watchdog for this run.
     */
    public long getInactivityTimeoutMsOrDefault() {
        if (inactivityTimeout == null) {
            return DEFAULT_INACTIVITY_TIMEOUT_MS;
        }
        return inactivityTimeout > 0 ? inactivityTimeout * 1000L : 0L;
    }

    /**
     * Check if tools are provided
     */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    /**
     * Check if auto-discovery is enabled
     */
    public boolean isAutoDiscoverEnabled() {
        return Boolean.TRUE.equals(autoDiscoverTools);
    }

    /**
     * Check if a specific service is approved for this conversation.
     *
     * @param serviceType The service type (e.g., "gmail", "slack")
     * @return true if the service is approved
     */
    public boolean isServiceApproved(String serviceType) {
        return approvedServices != null && approvedServices.contains(serviceType);
    }

    /**
     * Check if any approved services exist
     */
    public boolean hasApprovedServices() {
        return approvedServices != null && !approvedServices.isEmpty();
    }

    /**
     * Check if current message has attachments
     */
    public boolean hasCurrentMessageAttachments() {
        return currentMessageAttachments != null && !currentMessageAttachments.isEmpty();
    }

    /**
     * Get the call purpose, defaulting to {@link CallPurpose#MAIN} when unset.
     * Used by the centralized context-optimization pipeline to branch between
     * full optimization (MAIN) and fast path (CLASSIFY / GUARDRAIL).
     */
    public CallPurpose getPurposeOrDefault() {
        return CallPurpose.orDefault(purpose);
    }

    /**
     * Convenience predicate: is this a MAIN call that must traverse
     * the centralized pipeline?
     */
    public boolean isMainPurpose() {
        return getPurposeOrDefault() == CallPurpose.MAIN;
    }

    /**
     * Whether the caller supplied a layered {@link #systemBlocks} list. When
     * {@code true}, the loop forwards the list to {@code CompletionRequest}
     * and Claude emits a native {@code system: [...]} array with
     * {@code cache_control} on flagged blocks.
     */
    public boolean hasSystemBlocks() {
        return systemBlocks != null && !systemBlocks.isEmpty();
    }

    /**
     * Fold {@link #systemBlocks} into a single string using {@code "\n\n"}
     * separators, or return the legacy {@link #systemPrompt} when no blocks
     * are set. Blank blocks are skipped so optional sections don't leave
     * stray blank lines for providers that don't honor breakpoints.
     */
    public String effectiveSystemPrompt() {
        if (hasSystemBlocks()) {
            StringBuilder sb = new StringBuilder();
            for (SystemBlock block : systemBlocks) {
                if (block.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(block.text());
            }
            return sb.toString();
        }
        return systemPrompt;
    }
}
