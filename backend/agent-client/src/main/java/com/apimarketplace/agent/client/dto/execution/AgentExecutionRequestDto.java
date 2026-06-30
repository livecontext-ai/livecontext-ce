package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * DTO for requesting agent execution on agent-service.
 * Mirrors AgentRequest fields from orchestrator-service.
 * Used for full agent execution (with tool calls and streaming).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentExecutionRequestDto(
    String prompt,
    String systemPrompt,
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    List<Map<String, Object>> tools,
    Boolean autoDiscoverTools,
    Integer maxTools,
    Integer maxIterations,
    Integer executionTimeout,
    List<Map<String, Object>> conversationHistory,
    String tenantId,
    String runId,
    String nodeId,
    Map<String, Object> variables,
    Map<String, Object> credentials,
    Double maxCreditBudget,
    // Streaming context
    String streamChannelId,
    Integer itemIndex,
    Integer loopIteration,
    // Conversation context (Phase 6 - conversation-service remote dispatch)
    String conversationId,
    String streamingFormat,  // "workflow" (default) | "conversation"
    // Sub-agent parent-forwarding context (for real-time sub-agent visibility in parent conversation)
    String parentConversationId,
    String subAgentName,
    String subAgentAvatarUrl,
    String subAgentId,
    // Workflow cancel propagation - allows sub-agents to detect parent workflow cancellation
    String workflowRunId,  // checked via workflow:cancel:{workflowRunId} Redis key
    // File attachments (images, PDFs, text) - base64-encoded for bridge/remote dispatch
    List<Map<String, Object>> attachments,  // each: {type, mimeType, fileName, data (base64), extractedText}
    // Fleet real-time activity - agent entity ID for publishing to ws:agent:activity:{agentEntityId}
    String agentEntityId,
    // Bridge budget enforcement (P4 #14) - propagated to mcp/bridge so the JS guards
    // mirror Java's TenantBudgetGuard / AgentBudgetGuard. All optional: when omitted
    // the bridge falls back to "no budget enforcement" behaviour.
    //
    // tenantBalance:  remaining tenant credits at the start of the run
    // pricingRates:   optional override for the auth-service pricing snapshot
    //                 (each row: {provider, model, inputRate, outputRate, fixedCost})
    // (agentBudget is already carried by maxCreditBudget above.)
    Double tenantBalance,
    List<Map<String, Object>> pricingRates,
    /**
     * Credits already consumed by this agent within the current budget window.
     * Used by AgentBudgetGuard so that a half-consumed budget is not treated as
     * full. Resolved upstream by {@code BudgetResolver} (handles cumulative /
     * weekly / monthly reset modes). Defaults to 0 when omitted.
     */
    Double creditsConsumedSoFar,

    /**
     * Per-agent LoopDetector identical-call hard-stop override. {@code null} means
     * "fall back to platform default" ({@code AgentDefaultsConfig.loopIdenticalStop}).
     */
    Integer loopIdenticalStop,

    /**
     * Per-agent LoopDetector consecutive-call hard-stop override. {@code null} means
     * "fall back to platform default" ({@code AgentDefaultsConfig.loopConsecutiveStop}).
     */
    Integer loopConsecutiveStop,

    /**
     * Stable correlation ID minted by the dispatcher (= the UUID we'll persist as
     * {@code agent_executions.id}). Threaded into MCP credentials as
     * {@code __executionId__} so {@code task_claim} writes a claim log row keyed by
     * this id BEFORE the {@code agent_executions} row is INSERTed. Distinct from the
     * workflow-level {@link #runId} (= {@code workflow_run.id}): an agent execution
     * can be nested inside a workflow run, but the claim↔execution correlation
     * needs the per-execution id, not the workflow id.
     */
    String executionId,

    /**
     * Logical origin of the execution for fleet activity and observability
     * routing. Examples: WORKFLOW, CHAT, WIDGET, WEBHOOK, SCHEDULE, TASK,
     * TASK_REVIEW, SUB_AGENT. Null preserves legacy fallback behaviour.
     */
    String source,

    /**
     * Reasoning-effort level for CLI/bridge providers (claude-code, codex):
     * canonical lowercase {@code minimal|low|medium|high|xhigh}. Already
     * resolved by {@code ReasoningEffortResolver} (per-conversation override >
     * per-agent > per-model default) before this DTO is built, so the bridge
     * receives a single ready-to-map value. {@code null} ⇒ the bridge omits the
     * CLI flag and the CLI uses its own default. Ignored by non-bridge providers.
     */
    String reasoningEffort,

    /**
     * Canonical enabled MODULE keys (AgentModuleResolver vocabulary: catalog, table,
     * interface, agent, skill, workflow, application, web_search, files, image_generation),
     * derived from the agent's {@code toolsConfig} by the PRODUCER (workflow
     * {@code AgentNode} / chat {@code ConversationAgentService}). Drives core tool-SCHEMA
     * scoping on BOTH execution surfaces so the workflow path matches chat:
     * <ul>
     *   <li>direct loop - {@code AgentRemoteExecutionService} rebuilds the filtered core
     *       tool name set and calls {@code CoreToolsCache.getCoreTools(Set)};</li>
     *   <li>bridge - forwarded to the CLI session as {@code enabledModules} so
     *       {@code CliAgentService.resolveModules} scopes the MCP tools.</li>
     * </ul>
     * {@code null} ⇒ unrestricted (all core tools), preserving legacy behaviour for callers
     * that don't set it (e.g. classify/guardrail which already send no tools).
     */
    List<String> enabledModules
) {
    // PR16 - Note on org context propagation:
    //
    // Synchronous orchestrator → agent-service dispatch carries the workspace
    // identity via the X-Organization-ID / X-Organization-Role HEADERS that
    // the *-client buildHeaders() now forwards from the inbound request
    // (PR16 forwardOrgContextHeaders). The receiving AgentExecutionController
    // reads them from the inbound HTTP request and threads into the loop
    // context.
    //
    // Async queue dispatch has no inbound HTTP request to carry org headers.
    // Producers therefore serialize org context in credentials.__orgId__ /
    // credentials.__orgRole__ and, for DTOs that ignore nested unknown fields,
    // may also include top-level organizationId / organizationRole in the JSON
    // payload. The DTO intentionally keeps those top-level fields out of the
    // Java contract; AgentQueueWorkerService extracts them from raw JSON before
    // deserializing and binds TenantResolver on the worker thread.
    // Note on call purpose: this DTO is MAIN-only by construction - CLASSIFY and
    // GUARDRAIL have dedicated DTOs/endpoints (ClassifyRequestDto, GuardrailRequestDto).
    // Receiver (AgentRemoteExecutionService) sets CallPurpose.MAIN explicitly on the
    // built AgentLoopContext. If a future routing change adds a non-MAIN path through
    // this DTO, add a {@code String purpose} field and mirror it in buildContext.

    /**
     * Return a copy with {@link #reasoningEffort()} replaced. Used by agent-service
     * to inject the per-model default (lowest precedence) onto an incoming request
     * before forwarding it to the bridge, without mutating the immutable record.
     */
    public AgentExecutionRequestDto withReasoningEffort(String newReasoningEffort) {
        return new AgentExecutionRequestDto(
            prompt, systemPrompt, provider, model, temperature, maxTokens, tools,
            autoDiscoverTools, maxTools, maxIterations, executionTimeout, conversationHistory,
            tenantId, runId, nodeId, variables, credentials, maxCreditBudget, streamChannelId,
            itemIndex, loopIteration, conversationId, streamingFormat, parentConversationId,
            subAgentName, subAgentAvatarUrl, subAgentId, workflowRunId, attachments, agentEntityId,
            tenantBalance, pricingRates, creditsConsumedSoFar, loopIdenticalStop, loopConsecutiveStop,
            executionId, source, newReasoningEffort, enabledModules);
    }

    /**
     * Return a copy with {@link #provider()} replaced. Used by agent-service to
     * normalise a stale/blank provider against the model catalog before dispatch
     * - in particular re-routing a Claude bridge (CLI) model that was collapsed
     * to {@code "anthropic"} back to {@code "claude-code"} so it dispatches via
     * the bridge and passes through {@code BridgeAccessGuard}. No-op (returns
     * {@code this}) when the provider is unchanged.
     */
    public AgentExecutionRequestDto withProvider(String newProvider) {
        if (java.util.Objects.equals(newProvider, provider)) {
            return this;
        }
        return new AgentExecutionRequestDto(
            prompt, systemPrompt, newProvider, model, temperature, maxTokens, tools,
            autoDiscoverTools, maxTools, maxIterations, executionTimeout, conversationHistory,
            tenantId, runId, nodeId, variables, credentials, maxCreditBudget, streamChannelId,
            itemIndex, loopIteration, conversationId, streamingFormat, parentConversationId,
            subAgentName, subAgentAvatarUrl, subAgentId, workflowRunId, attachments, agentEntityId,
            tenantBalance, pricingRates, creditsConsumedSoFar, loopIdenticalStop, loopConsecutiveStop,
            executionId, source, reasoningEffort, enabledModules);
    }

    /**
     * Return a copy with {@link #provider()} AND {@link #model()} replaced by a CLI
     * bridge EXECUTION target (model execution links, cloud only). Used to dispatch
     * a billed model (e.g. {@code anthropic/claude-opus-4-8}) through a different
     * CLI bridge while the caller keeps the original BILLED identity for
     * observability + credit consumption. No-op when both are unchanged.
     */
    public AgentExecutionRequestDto withExecutionTarget(String newProvider, String newModel) {
        if (java.util.Objects.equals(newProvider, provider) && java.util.Objects.equals(newModel, model)) {
            return this;
        }
        return new AgentExecutionRequestDto(
            prompt, systemPrompt, newProvider, newModel, temperature, maxTokens, tools,
            autoDiscoverTools, maxTools, maxIterations, executionTimeout, conversationHistory,
            tenantId, runId, nodeId, variables, credentials, maxCreditBudget, streamChannelId,
            itemIndex, loopIteration, conversationId, streamingFormat, parentConversationId,
            subAgentName, subAgentAvatarUrl, subAgentId, workflowRunId, attachments, agentEntityId,
            tenantBalance, pricingRates, creditsConsumedSoFar, loopIdenticalStop, loopConsecutiveStop,
            executionId, source, reasoningEffort, enabledModules);
    }

    /**
     * Return a copy flagged for CLOUD model-execution-link "API mode": the bridge locks the
     * CLI to ONLY the platform MCP tools (no native Bash/Read/Write/Web), an empty cwd (no
     * AGENTS.md / CLAUDE.md / project files) and no account/CLI leakage, so a linked model
     * behaves like a plain API. The flag travels via the credentials map
     * ({@code __restrictedToolset__}) like the other execution-scoped markers
     * ({@code __executionId__}, {@code __approvedToolActions__}), so the 39-field positional
     * contract is untouched and every existing caller is unaffected. No-op when
     * {@code restricted} is false (default direct claude-code/codex/... stay unrestricted).
     */
    public AgentExecutionRequestDto withRestrictedToolset(boolean restricted) {
        if (!restricted) {
            return this;
        }
        java.util.Map<String, Object> creds = credentials == null
            ? new java.util.HashMap<>()
            : new java.util.HashMap<>(credentials);
        creds.put("__restrictedToolset__", true);
        return new AgentExecutionRequestDto(
            prompt, systemPrompt, provider, model, temperature, maxTokens, tools,
            autoDiscoverTools, maxTools, maxIterations, executionTimeout, conversationHistory,
            tenantId, runId, nodeId, variables, creds, maxCreditBudget, streamChannelId,
            itemIndex, loopIteration, conversationId, streamingFormat, parentConversationId,
            subAgentName, subAgentAvatarUrl, subAgentId, workflowRunId, attachments, agentEntityId,
            tenantBalance, pricingRates, creditsConsumedSoFar, loopIdenticalStop, loopConsecutiveStop,
            executionId, source, reasoningEffort, enabledModules);
    }
}
