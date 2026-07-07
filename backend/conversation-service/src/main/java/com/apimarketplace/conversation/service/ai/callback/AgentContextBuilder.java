package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.MessageAttachment;
import com.apimarketplace.agent.domain.SystemBlock;
import com.apimarketplace.agent.domain.ReasoningEffortResolver;
import com.apimarketplace.agent.domain.ThinkingLevel;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.apimarketplace.agent.skills.DefaultSkillsProvider;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.AttachmentService;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider;
import com.apimarketplace.conversation.service.ai.ThinkingLevelPinningService;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AgentConfig;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AgentSkillsSummary;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AvailableModel;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.FolderSummary;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.SkillSummary;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.ToolsConfig;
import com.apimarketplace.conversation.service.ai.CoreToolsProvider;
import com.apimarketplace.conversation.service.ai.SkillsSnapshotService;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.service.ai.schema.ModelTierMapper;
import com.apimarketplace.conversation.service.ai.schema.SchemaMode;
import com.apimarketplace.conversation.service.ai.schema.SchemaSlimExclusionPolicy;
import com.apimarketplace.conversation.service.ai.schema.SchemaSlimmer;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.ConversationMeta;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowContext;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowBuilderSessionContext;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds AgentLoopContext from ChatRequest.
 * Handles system prompts, tools discovery, and context configuration.
 *
 * Single Responsibility: Build properly configured agent contexts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentContextBuilder {

    private final ConversationHistoryConverter historyConverter;
    private final CoreToolsProvider coreToolsProvider;
    private final WorkflowContextProvider workflowContextProvider;
    private final AgentConfigProvider agentConfigProvider;
    private final ServiceApprovalService serviceApprovalService;
    private final AttachmentService attachmentService;
    private final SkillsSnapshotService skillsSnapshotService;
    private final ThinkingLevelPinningService thinkingLevelPinningService;
    private final ModelTierMapper modelTierMapper;
    private final SchemaSlimExclusionPolicy schemaSlimExclusionPolicy;
    private final HelpSeenRegistry helpSeenRegistry;
    private final MessageRepository conversationMessageRepository;

    /**
     * CE→cloud web-search relay gate (CE monolith only). Per-tenant runtime
     * filter: with the local engine disabled, web_search is exposed ONLY to
     * tenants whose effective LLM source is CLOUD (linked install) - unlinked /
     * BYOK tenants never see the tool. Field-injected and nullable so the
     * standalone cloud conversation-service (which does not scan the bean)
     * keeps its existing behavior.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.cloud.CeWebSearchRelayGate webSearchRelayGate;

    @Value("${conversation.agent.system-prompt:}")
    private String customSystemPrompt;

    @Value("${conversation.agent.max-iterations:10}")
    private int defaultMaxIterations;

    @Value("${conversation.agent.execution-timeout-seconds:3600}")
    private int defaultExecutionTimeoutSeconds;

    /**
     * Default output-token budget for general chat (no per-agent / per-conversation
     * override). Clamped below to the resolved model's real ceiling via
     * {@link com.apimarketplace.agent.config.MaxTokensClamp}, so this high default
     * never 400s a low-cap model (DeepSeek-chat = 8192).
     */
    @Value("${conversation.agent.max-tokens:16000}")
    private int defaultMaxTokens;

    private static final String NEW_CONVERSATION_INSTRUCTION = """
        [START - DO THIS IMMEDIATELY]
        1. Call set_conversation_title with a short title (3-6 words max)
        2. Execute the tools directly to accomplish the user's request
        """;

    private static final String FOLLOW_UP_INSTRUCTION = """
        [CONTINUATION]
        Execute the tools directly to accomplish the user's request.
        """;

    /**
     * F19: a DELEGATED task run (assignee, {@code source="TASK"}) or task-review
     * run (reviewer, {@code source="TASK_REVIEW"}) must always be able to reach the
     * task-lifecycle verbs - {@code task_complete} / {@code task_reject} for the
     * assignee, {@code task_approve} / {@code task_reject_review} for the reviewer -
     * which all live in the {@code agent} tool (the {@code "agent"} module). When the
     * (sub-)agent's configured toolset omits that module, the delegated run executes
     * and the loop ends, but the task is NEVER marked terminal and hangs in
     * {@code in_progress} forever (no completion, no error to the parent). This
     * force-includes the {@code "agent"} module for those delegated runs regardless
     * of toolsConfig, so it flows into BOTH the resolved tool list AND the
     * {@code enabledModules} that re-scope the direct loop / bridge downstream, and
     * the task can always reach a terminal state. No-op for non-delegated runs, when
     * the module is already present, or when {@code enabledModules} is null
     * (unrestricted - the agent already has every tool).
     *
     * <p>Package-private + static so it is unit-testable without standing up the
     * whole {@link #build} pipeline.
     */
    static Set<String> ensureTaskDelegationModules(Set<String> enabledModules, String source) {
        boolean isTaskDelegation = "TASK".equals(source) || "TASK_REVIEW".equals(source);
        if (!isTaskDelegation || enabledModules == null || enabledModules.contains("agent")) {
            return enabledModules;
        }
        Set<String> augmented = new java.util.LinkedHashSet<>(enabledModules);
        augmented.add("agent");
        log.info("Task delegation (source={}): force-included the 'agent' module so the delegate can reach "
                + "task_complete/task_reject (the task would otherwise hang in_progress)", source);
        return augmented;
    }

    /**
     * Build an AgentLoopContext from a ChatRequest.
     *
     * @param request The chat request
     * @param conversationId The conversation ID (for title generation)
     * @param streamId The stream ID (for tool callbacks like websearch screenshots)
     * @return Configured AgentLoopContext
     */
    public AgentLoopContext build(ChatRequest request, String conversationId, String streamId) {
        return build(request, conversationId, streamId, null);
    }

    /**
     * Same as {@link #build(ChatRequest, String, String)} but threads the dispatcher-minted
     * {@code executionId}. Caller responsible for passing the SAME UUID it used to publish
     * fleet WS events + carry into {@code AgentExecutionRequestDto} - that's how the claim
     * log keyed by MCP {@code __executionId__} aligns with the persisted
     * {@code agent_executions.id}.
     */
    public AgentLoopContext build(ChatRequest request, String conversationId, String streamId, String executionId) {
        String tenantId = request.getUserId();

        // Convert and log history (with tool result lookup from DB)
        List<Message> history = historyConverter.convert(request.getConversationHistory(), conversationId, tenantId);
        log.info("Agent context: {} messages in conversation history", history.size());
        historyConverter.logHistory(history);

        // Single DB query: load workflowId + agentId from conversation entity
        ConversationMeta meta = workflowContextProvider.getConversationMeta(conversationId);
        log.info("[ChatConfig] Conversation meta loaded: workflowId={}, agentId={}, chatConfig={}",
            meta.workflowId(), meta.agentId(), meta.chatConfig());

        // Resolve agentId: request takes priority, fallback to conversation entity
        String agentId = (request.getAgentId() != null && !request.getAgentId().isBlank())
            ? request.getAgentId()
            : meta.agentId();

        // Load agent config if agentId is available, or build from chat_config for general chat
        AgentConfig agentConfig = null;
        if (agentId != null && !agentId.isBlank()) {
            log.info("Resolved agentId: {} (from {})", agentId,
                request.getAgentId() != null ? "request" : "conversation entity");
            if (request.getOrgId() != null && !request.getOrgId().isBlank()) {
                agentConfig = agentConfigProvider.getAgentConfig(agentId, tenantId, request.getOrgId(), request.getOrgRole());
            } else {
                agentConfig = agentConfigProvider.getAgentConfig(agentId, tenantId, request.getOrgId());
            }
        } else if (meta.chatConfig() != null && !meta.chatConfig().isEmpty()) {
            agentConfig = buildChatConfig(meta.chatConfig());
            log.info("[ChatConfig] Built synthetic AgentConfig from chatConfig: temperature={}, maxTokens={}, maxIterations={}, systemPrompt={}, toolsConfig={}",
                agentConfig.temperature(), agentConfig.maxTokens(), agentConfig.maxIterations(),
                agentConfig.hasSystemPrompt() ? "present (" + agentConfig.systemPrompt().length() + " chars)" : "none",
                agentConfig.toolsConfig() != null ? agentConfig.toolsConfig().mode() : "null (all tools)");
        } else {
            log.info("[ChatConfig] No agentId and no chatConfig - using defaults for general chat");
        }

        // Get workflow context from pre-loaded meta (no extra DB query)
        WorkflowContext workflowContext = workflowContextProvider.getWorkflowContext(meta, tenantId);

        // Get active workflow builder session context (conversation-scoped)
        WorkflowBuilderSessionContext builderSessionContext = workflowContextProvider.getActiveWorkflowBuilderSession(tenantId, conversationId);

        // Build system prompt (with workflow context if available)
        boolean isNewConversation = historyConverter.isNewConversation(history);

        // Resolve toolsConfig early (needed for both prompt and tool filtering)
        ToolsConfig toolsConfig = (agentConfig != null) ? agentConfig.toolsConfig() : null;

        // ══════════════════════════════════════════════════════════════
        // Unified module resolution: same logic as workflow AgentNode
        // ══════════════════════════════════════════════════════════════
        List<ToolDefinition> tools = null;
        // Canonical enabled MODULE keys carried onto the context so a bridge dispatch
        // (ConversationAgentService → CLI) can scope the MCP tool set to the agent's
        // toolsConfig.mode. null ⇒ unrestricted (no toolsConfig → all modules).
        List<String> enabledModulesForContext = null;
        boolean isExternalSource = "WEBHOOK".equals(request.getSource()) || "SCHEDULE".equals(request.getSource()) || "TASK".equals(request.getSource()) || "TASK_REVIEW".equals(request.getSource());
        // Agent-bound conversations (agent chat, sub-agent, workflow agent) inherit their
        // title from the agent entity at creation time - the LLM must NOT override it.
        // Only general chat (no agentId, no workflow, interactive source) gets the
        // set_conversation_title tool + NEW_CONVERSATION_INSTRUCTION directive.
        boolean hasAgent = agentId != null && !agentId.isBlank();
        boolean shouldIncludeConversationTitle = isNewConversation && !workflowContext.isPresent() && !isExternalSource && !hasAgent;
        log.info("Context flags: source={}, isExternalSource={}, isNewConversation={}, hasAgent={}, shouldIncludeConversationTitle={}",
                request.getSource(), isExternalSource, isNewConversation, hasAgent, shouldIncludeConversationTitle);

        // Stage 1a.1 - layered system prompt. Blocks are collected into fixed
        // positions [0..9] so Claude can set cache breakpoints on the stable
        // prefix (block 0) and on the end of the skills segment (block 3).
        // Non-Claude providers concatenate blocks into a single string via
        // CompletionRequest.effectiveSystemPrompt(). Position semantics:
        //   [0] STATIC base system prompt (workflow / default / custom / agent)  ← cache breakpoint
        //   [1] Tools-config restriction instructions
        //   [2] Default skills tree (default:* ids from request)
        //   [3] Agent skills tree + user-request skills tree                     ← cache breakpoint
        //   [4] Task delegation summary
        //   [5] Workflow builder session context (plan JSON)
        //   [6] Workflow context (reserved - folded into block 0 for now)
        //   [7] Custom agent system prompt
        //   [8] TASK_REVIEW mode instructions
        //   [9] NEW / FOLLOW_UP conversation instruction
        String[] blockSlots = new String[10];

        // Block 0: STATIC base system
        if (toolsConfig != null) {
            Map<String, Object> toolsConfigMap = toolsConfig.toMap();
            Set<String> enabledModules = AgentModuleResolver.resolveEnabledModules(toolsConfigMap);
            // F19: a delegated TASK/TASK_REVIEW run must keep the `agent` module even
            // when its configured toolset omits it, else the task hangs in_progress
            // forever (see helper javadoc). Done at the MODULE level so it flows into
            // BOTH the resolved tool list (coreToolNames) AND enabledModulesForContext
            // (which re-scopes the direct loop + bridge downstream).
            enabledModules = ensureTaskDelegationModules(enabledModules, request.getSource());
            enabledModulesForContext = List.copyOf(enabledModules);
            DefaultSystemPrompts.ModularPromptResult promptResult =
                DefaultSystemPrompts.build(enabledModules, true);
            blockSlots[0] = promptResult.systemPrompt();
            tools = coreToolsProvider.getCoreTools(promptResult.coreToolNames(), shouldIncludeConversationTitle);
            log.info("{} toolsConfig: enabled modules={}, core tools={}",
                agentConfig.agentId() != null ? "Agent " + agentConfig.agentId() : "Chat config",
                enabledModules, promptResult.coreToolNames());
        } else if (agentConfig != null && agentConfig.agentId() != null) {
            DefaultSystemPrompts.ModularPromptResult agentPromptResult = DefaultSystemPrompts.buildAgentDefault();
            blockSlots[0] = agentPromptResult.systemPrompt();
            tools = coreToolsProvider.getCoreTools(agentPromptResult.coreToolNames(), shouldIncludeConversationTitle);
            log.info("Agent {} without toolsConfig: using concise agent prompt, all tools available", agentConfig.agentId());
        } else {
            // General chat: workflow-specific / custom / default base.
            // Block 5 (workflow builder session) and block 9 (conversation
            // instructions) are now collected separately, so the base here is
            // strictly the stable prefix suitable for caching.
            blockSlots[0] = buildBasePrompt(workflowContext);
        }
        if (blockSlots[0] == null) {
            blockSlots[0] = "";
        }
        if (tools == null) {
            // General chat / no-agent path: load unfiltered core tools.
            tools = getCoreTools(shouldIncludeConversationTitle);
        }

        // CE→cloud web-search relay: per-tenant runtime exposure. Drops web_search
        // for tenants that cannot use it (local engine disabled AND tenant not
        // cloud-linked with the CLOUD source). No-op in cloud (gate null or local
        // engine enabled).
        boolean webSearchUnavailableOnInstall = false;
        if (webSearchRelayGate != null) {
            // Install-level unavailability (component off / not linked) - NOT a per-agent
            // toolsConfig choice: the notice is only flagged when the module was otherwise
            // wanted, so it never contradicts a deliberate "no web_search" agent config.
            // Availability is resolved ONCE (it can cost an HTTP roundtrip on relay-wired
            // CE installs) and reused for both the tool drop and the notice.
            boolean moduleWanted = enabledModulesForContext == null
                || enabledModulesForContext.contains(DefaultSystemPrompts.WEB_SEARCH.key());
            boolean containsWebSearch = tools != null && tools.stream()
                .anyMatch(t -> t != null
                    && com.apimarketplace.agent.cloud.CeWebSearchRelayGate.WEB_SEARCH_TOOL_NAME.equals(t.name()));
            if ((moduleWanted || containsWebSearch)
                && !webSearchRelayGate.isWebSearchAvailable(tenantId)) {
                tools = com.apimarketplace.agent.cloud.CeWebSearchRelayGate.removeWebSearch(tools);
                webSearchUnavailableOnInstall = moduleWanted;
            }
        }

        // Block 1: tools-config restriction
        if (toolsConfig != null) {
            blockSlots[1] = buildToolsConfigPrompt(toolsConfig);
        }
        if (webSearchUnavailableOnInstall) {
            // Tell the agent WHY web_search is missing and what to answer (enable path),
            // instead of leaving it to guess. Appended to block 1 so block 0 stays the
            // stable cacheable prefix.
            blockSlots[1] = (blockSlots[1] == null ? "" : blockSlots[1])
                + DefaultSystemPrompts.WEB_SEARCH_UNAVAILABLE_NOTICE;
        }

        // Blocks 2 + 3: skills (default skills at [2], agent + user-request skills at [3])
        List<String> requestSkillIds = request.getDefaultSkillIds();
        boolean hasPerConversationSkillOverride = request.isDefaultSkillIdsProvided();
        if (!hasPerConversationSkillOverride) {
            List<String> persistedSkillIds = readPersistedDefaultSkillIds(meta.chatConfig());
            if (persistedSkillIds != null) {
                requestSkillIds = persistedSkillIds;
                hasPerConversationSkillOverride = true;
            }
        }
        List<String> defaultIds = List.of();
        List<String> userSkillIds = List.of();
        if (hasPerConversationSkillOverride && requestSkillIds != null && !requestSkillIds.isEmpty()) {
            defaultIds = requestSkillIds.stream()
                .filter(id -> id.startsWith("default:"))
                .collect(Collectors.toList());
            userSkillIds = requestSkillIds.stream()
                .filter(id -> !id.startsWith("default:"))
                .collect(Collectors.toList());
        }

        if (!defaultIds.isEmpty()) {
            String defaultSkillsPrompt = DefaultSkillsProvider.buildPromptSection(defaultIds);
            if (defaultSkillsPrompt != null && !defaultSkillsPrompt.isEmpty()) {
                blockSlots[2] = defaultSkillsPrompt;
                log.info("Injected {} default skills into system prompt", defaultIds.size());
            }
        }

        StringBuilder block3 = new StringBuilder();
        // Agent skills tree
        // Stage 1a.2: try the per-conversation snapshot first; only on miss do
        // we pay the HTTP roundtrip to agent-service and render the tree.
        //
        // Key caveat: the key is derived from (agentId, empty-skill-list) - we
        // can't know the actual skill ids without paying the HTTP fetch we're
        // trying to skip. A skill add/remove on the agent is therefore only
        // invalidated via the service's TTL (5 min), not instantaneously. This
        // is deliberately accepted until R37 (Redis pub/sub + agentSkillTreeVersion)
        // lands; see SkillsSnapshotService javadoc.
        if (agentConfig != null) {
            String agentSnapshotKey = SkillsSnapshotService.deriveKey(agentId, List.of());
            java.util.Optional<String> cached = skillsSnapshotService.loadIfFresh(conversationId, agentSnapshotKey);
            if (cached.isPresent()) {
                appendBlockFragment(block3, cached.get());
                log.debug("Used cached skills tree for agent {} (conv {})", agentConfig.agentId(), conversationId);
            } else {
                AgentSkillsSummary skillsSummary = agentConfigProvider.getAgentSkillsSummary(agentId, tenantId);
                if (skillsSummary != null && !skillsSummary.skills().isEmpty()) {
                    String skillsPrompt = buildSkillsTreePrompt(skillsSummary);
                    appendBlockFragment(block3, skillsPrompt);
                    skillsSnapshotService.save(conversationId, agentSnapshotKey, skillsPrompt);
                    log.info("Injected skills tree with {} skills and {} folders for agent {}",
                        skillsSummary.skills().size(), skillsSummary.folders().size(), agentConfig.agentId());
                }
            }
        }
        // User-request skills tree
        // Stage 1a.2: snapshot keyed by sorted(userSkillIds) so toggling
        // skills in the UI mid-conversation invalidates the cache on the
        // next turn (new id set → new key → re-fetch + re-persist).
        if (!userSkillIds.isEmpty()) {
            String requestSnapshotKey = SkillsSnapshotService.deriveKey(null, userSkillIds);
            java.util.Optional<String> cachedRequestSkills =
                skillsSnapshotService.loadIfFresh(conversationId, requestSnapshotKey);
            if (cachedRequestSkills.isPresent()) {
                appendBlockFragment(block3, cachedRequestSkills.get());
                log.debug("Used cached request-skills tree ({} ids) for conv {}",
                    userSkillIds.size(), conversationId);
            } else {
                AgentSkillsSummary userSkillsSummary = agentConfigProvider.getSkillsSummaryByIds(userSkillIds, tenantId);
                if (userSkillsSummary != null && !userSkillsSummary.skills().isEmpty()) {
                    String userSkillsPrompt = buildSkillsTreePrompt(userSkillsSummary);
                    appendBlockFragment(block3, userSkillsPrompt);
                    skillsSnapshotService.save(conversationId, requestSnapshotKey, userSkillsPrompt);
                    log.info("Injected {} user skills into system prompt for general chat",
                        userSkillsSummary.skills().size());
                }
            }
        } else if (agentConfig == null && defaultIds.isEmpty() && !hasPerConversationSkillOverride) {
            // V275/V276 (2026-05-21) - general chat with no per-conversation
            // override from the client: fall back to the user's effective
            // default-active set (skill.is_default_active overlaid with
            // user_skill_overrides). Replaces the legacy localStorage seed
            // that lived in frontend/hooks/useDefaultSkills.ts.
            //
            // Per-conv override still wins when defaultSkillIds IS passed -
            // that path goes through the userSkillIds branch above and skips
            // this fallback entirely (preserves the "reactivate just for this
            // conversation" UX).
            //
            // Snapshot-cached just like the other two skill branches: every
            // new general-chat turn would otherwise pay a cross-service HTTP
            // roundtrip. The sentinel discriminator "__v276_default_active__"
            // keys the cache so it never collides with an agent's snapshot
            // (which uses real agentIds). The user-toggle UX self-invalidates
            // via the per-conversation key + the service's 5-min TTL.
            String effectiveSnapshotKey = SkillsSnapshotService.deriveKey(
                "__v276_default_active__", List.of());
            java.util.Optional<String> cachedEffective =
                skillsSnapshotService.loadIfFresh(conversationId, effectiveSnapshotKey);
            if (cachedEffective.isPresent()) {
                appendBlockFragment(block3, cachedEffective.get());
                log.debug("Used cached V276 effective-default skills tree for conv {}", conversationId);
            } else {
                AgentSkillsSummary effectiveDefaults = agentConfigProvider.getDefaultActiveSkillsSummary(tenantId);
                if (effectiveDefaults != null && !effectiveDefaults.skills().isEmpty()) {
                    String effectiveDefaultsPrompt = buildSkillsTreePrompt(effectiveDefaults);
                    appendBlockFragment(block3, effectiveDefaultsPrompt);
                    skillsSnapshotService.save(conversationId, effectiveSnapshotKey, effectiveDefaultsPrompt);
                    log.info("Injected {} effective default-active skills (V276) into system prompt for general chat",
                        effectiveDefaults.skills().size());
                }
            }
        }
        if (block3.length() > 0) {
            blockSlots[3] = block3.toString();
        }

        // Block 4: task delegation summary (silent no-op when the agent has no tasks;
        // RPC failures return "").
        if (agentConfig != null && agentId != null) {
            String taskSection = agentConfigProvider.getTaskSummarySection(agentId, tenantId);
            if (taskSection != null && !taskSection.isBlank()) {
                blockSlots[4] = taskSection;
                log.info("Injected task delegation summary for agent {}", agentConfig.agentId());
            }
        }

        // Block 5: workflow builder session context
        if (builderSessionContext.hasActiveSession()) {
            blockSlots[5] = buildWorkflowBuilderSessionPrompt(builderSessionContext);
            log.info("Injecting workflow builder session context: {} '{}' ({} nodes)",
                builderSessionContext.sessionId(), builderSessionContext.workflowName(),
                builderSessionContext.nodeCount());
        }

        // Block 6: durable COLD compaction summary (Stage 5.5). When the
        // conversation has been compacted, conversation-service persists a
        // distilled summary of the masked-out turns in summary_cold; inject it
        // here so the LLM recalls prior decisions / resolved IDs / completed
        // actions that the HOT/WARM history window no longer shows in full.
        // This is the single layer BOTH chat paths share - the flattened
        // systemPrompt feeds the bridge/CLI path and the systemBlocks list feeds
        // the direct-API path - so both recall the same context. The renderer is
        // defensive: a null/empty/malformed summary yields "" (no-op), so short
        // conversations and bad rows alike leave this block empty.
        String coldSummaryBlock = ColdSummaryContextRenderer.render(meta.summaryCold());
        if (!coldSummaryBlock.isEmpty()) {
            blockSlots[6] = coldSummaryBlock;
            log.info("Injected COLD compaction summary into system prompt ({} chars)", coldSummaryBlock.length());
        }

        // Block 7: custom agent / chatConfig system prompt (extends, never replaces)
        if (agentConfig != null && agentConfig.hasSystemPrompt()) {
            blockSlots[7] = agentConfig.systemPrompt();
            log.info("Appending custom system prompt from {}", agentConfig.agentId() != null
                ? "agent: " + agentConfig.name() + " (" + agentConfig.agentId() + ")"
                : "chat config");
        }

        // Block 8: TASK_REVIEW (tool-list restriction applied later)
        if ("TASK_REVIEW".equals(request.getSource())) {
            blockSlots[8] = "[TASK REVIEW MODE]\n"
                + "You are reviewing a delegated task. You MUST call task_approve or task_reject_review using the agent tool. "
                + "Do not use any other tools.";
        }

        // Block 9: NEW / FOLLOW_UP conversation instruction (non-workflow, non-external).
        // Agent-bound conversations never receive the NEW instruction - it points the
        // LLM at set_conversation_title, which is not wired for agent chats. They fall
        // through to FOLLOW_UP on every turn.
        if (!workflowContext.isPresent() && conversationId != null && !isExternalSource) {
            blockSlots[9] = (isNewConversation && !hasAgent) ? NEW_CONVERSATION_INSTRUCTION : FOLLOW_UP_INSTRUCTION;
        }

        // Assemble blocks - breakpoints on [0] (stable base) and [3] (end of skills).
        List<SystemBlock> systemBlocks = new ArrayList<>(10);
        for (int i = 0; i < blockSlots.length; i++) {
            String text = blockSlots[i] == null ? "" : blockSlots[i];
            boolean breakpoint = (i == 0 || i == 3);
            systemBlocks.add(breakpoint ? SystemBlock.breakpoint(text) : SystemBlock.of(text));
        }

        // Flatten for legacy string-based downstream callers. Blank blocks
        // are skipped so optional sections don't leave stray separators.
        String systemPrompt = concatenateBlocks(systemBlocks);

        // Resolve provider and model: agent config overrides frontend selection
        String provider = (agentConfig != null && agentConfig.hasProvider())
            ? agentConfig.modelProvider() : request.getProvider();
        String model = (agentConfig != null && agentConfig.hasModel())
            ? agentConfig.modelName() : request.getModel();

        if (agentConfig != null && agentConfig.hasModel()) {
            log.info("Using agent model: {} / {} (overriding request: {} / {})",
                provider, model, request.getProvider(), request.getModel());
        }

        // Resolve temperature, maxTokens, and maxIterations from agent config.
        // maxTokens: per-agent / per-conversation override wins; otherwise the
        // platform default (general chat). Clamped to the model's real ceiling
        // just before the context is built (see below).
        Double temperature = (agentConfig != null) ? agentConfig.temperature() : null;
        Integer maxTokens = (agentConfig != null && agentConfig.maxTokens() != null)
            ? agentConfig.maxTokens() : defaultMaxTokens;
        Integer maxIterations = (agentConfig != null && agentConfig.maxIterations() != null)
            ? agentConfig.maxIterations() : null;

        // ── TASK_REVIEW source: restrict to agent tool only (review hint already in block 8) ──
        if ("TASK_REVIEW".equals(request.getSource())) {
            tools = tools.stream()
                .filter(t -> AGENT_TOOL_NAMES.contains(t.name()))
                .collect(Collectors.toList());
            log.info("TASK_REVIEW source: restricted tools to agent only ({} tools)", tools.size());
        }

        // Stage 4a - JIT schema slimming. Resolve model tier from the cached
        // platform catalog and map (provider, modelId, tier) → SchemaMode. SLIM
        // rewrites every non-excluded tool's parameter schema to a names-only
        // stub that points the LLM at help(topic=<action>); top/high/mid tiers
        // bootstrap from help calls. Budget and unknown tiers stay FULL so an
        // unclassified model never silently regresses tool accuracy.
        //
        // Stage 4a.4 - per-conversation help-seen gate: a tool that received a
        // recent help response (fresh within hotWarmTurnBudget) is promoted
        // back to FULL for the current turn so the LLM can immediately act on
        // the help it just saw, instead of slimming it away again.
        tools = applyJitSchemaSlim(tools, provider, model, conversationId);

        log.info("[ChatConfig] Effective values: temperature={}, maxTokens={}, maxIterations={}, toolCount={}, systemPromptLength={}",
            temperature, maxTokens,
            maxIterations != null ? maxIterations : defaultMaxIterations,
            tools.size(),
            systemPrompt != null ? systemPrompt.length() : 0);

        logTools(tools);

        // Build credentials with conversationId and turnId for tools that need it
        Map<String, Object> credentials = new HashMap<>();
        if (conversationId != null) {
            credentials.put("conversationId", conversationId);
        }
        // Unique ID per user message - used by rate limiters to scope limits per message
        credentials.put("turnId", java.util.UUID.randomUUID().toString());
        // Initialize agent depth for sub-agent execution tracking (0 = top-level agent)
        credentials.put("__agent_depth__", 0);
        // Agent ID for tracking which agent initiated tool calls (nullable for direct chat)
        if (agentId != null && !agentId.isBlank()) {
            credentials.put("__agentId__", agentId);
        }
        // Agent credit budget for bridge-path budget enforcement
        if (agentConfig != null && agentConfig.creditBudget() != null) {
            credentials.put("__creditBudget__", agentConfig.creditBudget());
            credentials.put("__creditsConsumed__", agentConfig.creditsConsumed() != null ? agentConfig.creditsConsumed() : 0.0);
        }
        // Stream ID for tool callbacks (e.g., websearch screenshot streaming)
        if (streamId != null) {
            credentials.put("__streamId__", streamId);
        }

        // Add workflow context to credentials for tools that need it (e.g., workflow guard)
        if (workflowContext.isPresent()) {
            credentials.put("__viewingWorkflowId__", workflowContext.workflowId());
            credentials.put("__viewingWorkflowName__", workflowContext.workflowName());
            log.debug("Added workflow context to credentials: {} ({})",
                workflowContext.workflowName(), workflowContext.workflowId());
        }

        // Pass tool/workflow restrictions via credentials for enforcement at execution time
        if (toolsConfig != null) {
            applyToolsConfigCredentials(credentials, toolsConfig);
        }

        // Pass org context for deny-list filtering in tool modules
        if (request.getOrgId() != null && !request.getOrgId().isBlank()) {
            credentials.put("__orgId__", request.getOrgId());
            if (request.getOrgRole() != null) {
                credentials.put("__orgRole__", request.getOrgRole());
            }
        }
        // Pass the platform role set (X-User-Roles) so service-layer tool modules can
        // resolve admin status without an HttpServletRequest - e.g. AgentHelpModule
        // hides admin-only CLI-bridge models from non-admin agents. Threaded
        // deterministically via credentials (not RequestContextHolder) because tool
        // calls may run on async streaming threads with no servlet request bound.
        if (request.getUserRoles() != null && !request.getUserRoles().isBlank()) {
            credentials.put("__userRoles__", request.getUserRoles());
        }
        if (request.getReviewerExecutionId() != null && !request.getReviewerExecutionId().isBlank()) {
            credentials.put("__reviewerExecutionId__", request.getReviewerExecutionId());
        }

        // Approved services are no longer cached in DB - agent relies on conversation history
        Set<String> approvedServices = Set.of();

        // Load attachments for current message if present
        List<MessageAttachment> currentAttachments = null;
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            currentAttachments = attachmentService.loadAttachments(request.getAttachments(), tenantId, request.getOrgId());
            log.info("Loaded {} attachments for current message", currentAttachments.size());
        }

        // Resolve execution timeout: chat_config can override the default (clamped to 10-7200s)
        int effectiveTimeout = defaultExecutionTimeoutSeconds;
        if (meta.chatConfig() != null && meta.chatConfig().get("executionTimeout") instanceof Number n) {
            effectiveTimeout = Math.max(10, Math.min(7200, n.intValue()));
        }

        // Resolve inactivity watchdog window (mirrors executionTimeout above): chat_config can set a
        // per-conversation window (0 = disabled, 10-7200s). Carried on the credentials map so both the
        // in-process loop (AgentLoopService.resolveInactivityWindowMs) and the bridge honor it; absent
        // => the platform 5-minute default applies.
        if (meta.chatConfig() != null && meta.chatConfig().get("inactivityTimeout") instanceof Number inact) {
            int inactSecs = inact.intValue();
            if (inactSecs == 0 || (inactSecs >= 10 && inactSecs <= 7200)) {
                credentials.put("__inactivityTimeoutSeconds__", inactSecs);
            }
        }

        // Per-agent / per-conversation loop-detector thresholds (V100 columns for agent scope,
        // chatConfig.turnLimits for conversation scope). Both paths converge on AgentConfig -
        // null ⇒ LoopDetector uses platform defaults.
        Integer loopIdenticalStop = (agentConfig != null) ? agentConfig.loopIdenticalStop() : null;
        Integer loopConsecutiveStop = (agentConfig != null) ? agentConfig.loopConsecutiveStop() : null;

        // Conversation-scope uniform per-resource cap travels via credentials (under the
        // __chat*__ namespace) so tool modules (agent/skill/sub_agent/interface/workflow/table)
        // can use it as a fallback when the caller-agent entity has no override of its own. Safe
        // to propagate in all scopes: the caller-agent column always wins in the shared resolver
        // (see com.apimarketplace.agent.config.GuardOverrides#resolve).
        if (agentConfig != null && agentConfig.maxPerResourcePerTurn() != null) {
            credentials.put(com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN,
                agentConfig.maxPerResourcePerTurn());
        }

        // Stage 1b.1 - resolve (and pin, on Claude) the conversation's thinking
        // level before handing off to the agent loop. Non-Claude providers
        // receive null here and fall through to per-iteration auto-resolution.
        // userMsgChars is the length of THIS turn's user prompt: we pin on the
        // first MAIN resolution, but the call site still supplies the current
        // turn's shape so Gemini/OpenAI (which don't pin) get fresh adaptive
        // tiering each turn.
        int userMsgChars = request.getMessage() != null ? request.getMessage().length() : 0;
        ThinkingLevel resolvedThinkingLevel = thinkingLevelPinningService.resolveAndPin(
            conversationId, provider, model, CallPurpose.MAIN, tools.size(), userMsgChars);

        // Reasoning effort for CLI/bridge providers (claude-code, codex). Precedence:
        // per-conversation override (chat selector) > per-agent setting > per-model admin
        // default (platform catalog). All null/blank ⇒ bridge omits the CLI flag (CLI default).
        AvailableModel effortEntry = lookupCatalogModel(provider, model);
        if (effortEntry == null) {
            effortEntry = agentConfigProvider.findAvailableModelByModelId(model);
        }
        String reasoningEffort = ReasoningEffortResolver.resolve(
            request.getReasoningEffort(),
            agentConfig != null ? agentConfig.reasoningEffort() : null,
            effortEntry != null ? effortEntry.defaultReasoningEffort() : null);

        // Clamp the output budget to the resolved model's real ceiling (catalog
        // maxOutputTokens) so a high default (16000) never 400s a low-cap model
        // (DeepSeek-chat = 8192). Unknown cap ⇒ MaxTokensClamp's safe 8192 floor.
        Integer modelOutputCap = effortEntry != null ? effortEntry.maxOutputTokens() : null;
        Integer requestedMaxTokens = maxTokens;
        maxTokens = com.apimarketplace.agent.config.MaxTokensClamp.clamp(maxTokens, modelOutputCap);
        if (maxTokens != null && !maxTokens.equals(requestedMaxTokens)) {
            log.info("[ChatConfig] Clamped maxTokens {} → {} to {} output ceiling (cap={})",
                requestedMaxTokens, maxTokens, model, modelOutputCap);
        }

        return AgentLoopContext.builder()
            .provider(provider)
            .model(model)
            .systemPrompt(systemPrompt)
            .systemBlocks(systemBlocks)
            .userPrompt(request.getMessage())
            .currentMessageAttachments(currentAttachments)
            .conversationHistory(history)
            .tools(tools)
            // Chat ships meta-tools (agent, application, catalog, …) whose action
            // dispatch already covers the full catalog. RRF auto-discovery in
            // AgentLoopService bypasses SchemaSlimmer, so discovered tools would
            // arrive FULL - inflating prompts without adding capability. Only
            // fall back to auto-discovery if the meta-tool list is empty (degraded
            // CoreToolsProvider), so we don't strand the agent with zero tools.
            .autoDiscoverTools(tools.isEmpty())
            .maxIterations(maxIterations != null ? maxIterations : defaultMaxIterations)
            .executionTimeout(effectiveTimeout)
            .temperature(temperature)
            .maxTokens(maxTokens)
            .tenantId(tenantId)
            .userRoles(request.getUserRoles())
            .credentials(credentials)
            .approvedServices(approvedServices)
            .loopIdenticalStop(loopIdenticalStop)
            .loopConsecutiveStop(loopConsecutiveStop)
            .reasoningEffort(reasoningEffort)
            .enabledModules(enabledModulesForContext)
            .purpose(CallPurpose.MAIN)
            .thinkingLevel(resolvedThinkingLevel)
            .executionId(executionId)
            .build();
    }

    /**
     * Build a synthetic AgentConfig from per-conversation chat_config JSONB.
     * Used for general chat (no agentId) when the user has customized settings.
     *
     * <p>Turn-limit overrides live under {@code chatConfig.turnLimits.*} and share key
     * names with {@link com.apimarketplace.agent.config.GuardOverrides#KEYS}. Values are
     * clamped to positive integers so the downstream resolver never sees junk. Range
     * violations beyond "positive" are caught at write time by
     * {@code ConversationCommandService} via the shared {@code GuardOverrides.validate}
     * - at read time we apply the stored value as-is (clamped).
     */
    private AgentConfig buildChatConfig(Map<String, Object> chatConfig) {
        // Extract and clamp values to safe ranges
        Double temperature = null;
        if (chatConfig.get("temperature") instanceof Number n) {
            temperature = Math.max(0.0, Math.min(2.0, n.doubleValue()));
        }
        Integer maxTokens = null;
        if (chatConfig.get("maxTokens") instanceof Number n) {
            maxTokens = Math.max(1, Math.min(128000, n.intValue()));
        }
        Integer maxIterations = null;
        if (chatConfig.get("maxIterations") instanceof Number n) {
            maxIterations = Math.max(1, Math.min(1000, n.intValue()));
        }
        String systemPrompt = chatConfig.get("systemPrompt") instanceof String s && !s.isBlank()
            ? (s.length() > 10000 ? s.substring(0, 10000) : s) : null;

        // Build ToolsConfig only if toolsMode is explicitly set to something other than "all"
        String toolsMode = chatConfig.get("toolsMode") instanceof String s ? s : "all";
        // Sanitize toolsMode to known values
        if (!"all".equals(toolsMode) && !"none".equals(toolsMode)) {
            toolsMode = "all";
        }
        Boolean webSearch = chatConfig.get("webSearch") instanceof Boolean b ? b : null;

        ToolsConfig toolsConfig = null;
        if (!"all".equals(toolsMode) || Boolean.FALSE.equals(webSearch)) {
            toolsConfig = new ToolsConfig(toolsMode, List.of(), null, null, null, null, null,
                webSearch, null, null, null, null, null, null);
        }

        // Conversation-scope guard overrides - chatConfig.turnLimits.{key}. Null if absent.
        Map<String, Object> turnLimits = chatConfig.get("turnLimits") instanceof Map<?, ?> m
            ? castToStringObjectMap(m) : null;
        Integer maxPerResourcePerTurn = readPositiveInt(turnLimits, com.apimarketplace.agent.config.GuardOverrides.MAX_PER_RESOURCE_PER_TURN);
        Integer loopIdenticalStop = readPositiveInt(turnLimits, com.apimarketplace.agent.config.GuardOverrides.LOOP_IDENTICAL_STOP);
        Integer loopConsecutiveStop = readPositiveInt(turnLimits, com.apimarketplace.agent.config.GuardOverrides.LOOP_CONSECUTIVE_STOP);

        return new AgentConfig(null, null, systemPrompt, null, null,
            temperature, maxTokens, maxIterations, toolsConfig, null, null,
            maxPerResourcePerTurn, loopIdenticalStop, loopConsecutiveStop);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToStringObjectMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /**
     * Read a positive integer from a nested map, returning null when the key is
     * absent/non-numeric/≤0. Keeps {@link #buildChatConfig} terse and centralises the
     * "positive only" rule that matches {@link com.apimarketplace.agent.config.GuardOverrides#validate}.
     */
    private static Integer readPositiveInt(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object raw = map.get(key);
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : null;
        }
        return null;
    }

    /**
     * Stage 1a.1 - produce only the STATIC base prompt (block 0), without
     * workflow builder session context or NEW/FOLLOW_UP instructions. Those
     * are now collected into their own blocks ([5] and [9]) so Claude can
     * close a cache breakpoint at the end of the stable base.
     */
    private String buildBasePrompt(WorkflowContext workflowContext) {
        if (workflowContext.isPresent()) {
            log.info("Building workflow-specific prompt for workflow: {} ({})",
                workflowContext.workflowName(), workflowContext.workflowId());
            return DefaultSystemPrompts.buildWorkflowPrompt(
                workflowContext.workflowName(),
                workflowContext.workflowId(),
                workflowContext.workflowStatus(),
                workflowContext.flowDiagram(),
                workflowContext.datasourceId(),
                workflowContext.lastRunInfo(),
                workflowContext.readOnly()
            );
        }
        if (hasCustomPrompt()) {
            return customSystemPrompt;
        }
        // General chat path (no workflow context, no scoped agent, no toolsConfig).
        // The marketplace-discovery cue ("is there an existing app for this?" before
        // reaching for workflow(action='init')) now lives on the application module
        // line, so the default prompt already carries it - no separate reflex variant.
        return DefaultSystemPrompts.getDefault();
    }

    /**
     * Append a non-blank fragment to a block buffer, inserting a blank-line
     * separator only when the buffer already has content. Callers that pass
     * {@code null} or blank are no-ops.
     */
    private static void appendBlockFragment(StringBuilder buffer, String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return;
        }
        if (buffer.length() > 0) {
            buffer.append("\n\n");
        }
        buffer.append(fragment);
    }

    private List<String> readPersistedDefaultSkillIds(Map<String, Object> chatConfig) {
        if (chatConfig == null || chatConfig.isEmpty()) {
            return null;
        }
        Object raw = chatConfig.get("defaultSkillIds");
        if (!(raw instanceof List<?> values)) {
            return null;
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(id -> !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Flatten the layered block list into a single string for providers that
     * don't honor breakpoints (and for legacy callers on {@code AgentLoopContext.systemPrompt()}).
     * Blank blocks are skipped so optional sections don't leave stray separators.
     */
    private static String concatenateBlocks(List<SystemBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (SystemBlock block : blocks) {
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

    private String buildWorkflowBuilderSessionPrompt(WorkflowBuilderSessionContext session) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════════\n");
        sb.append("                    ACTIVE WORKFLOW BUILDER SESSION\n");
        sb.append("═══════════════════════════════════════════════════════════════════\n");
        sb.append("⚠️ DO NOT call workflow(action='init') - session already active!\n\n");

        // Session info
        sb.append("📋 SESSION INFO\n");
        sb.append("   Name: ").append(session.workflowName()).append("\n");
        if (session.workflowDescription() != null && !session.workflowDescription().isBlank()) {
            sb.append("   Description: ").append(session.workflowDescription()).append("\n");
        }
        if (session.draftId() != null) {
            sb.append("   Draft ID: ").append(session.draftId()).append(" (loaded from existing workflow)\n");
        }
        sb.append("\n");

        // Current plan
        sb.append("📊 CURRENT PLAN\n");
        Map<String, Object> plan = session.plan();
        if (plan != null) {
            appendPlanSection(sb, "triggers", plan.get("triggers"));
            appendPlanSection(sb, "mcps", plan.get("mcps"));
            appendPlanSection(sb, "cores", plan.get("cores"));
            appendPlanSection(sb, "edges", plan.get("edges"));
            appendPlanSection(sb, "interfaces", plan.get("interfaces"));
            appendPlanSection(sb, "tables", plan.get("tables"));
        } else {
            sb.append("   (empty plan)\n");
        }
        sb.append("\n");

        // Context (rules, variable_syntax, actions, NEXT)
        Map<String, Object> context = session.context();
        if (context != null) {
            // Rules
            sb.append("📝 RULES\n");
            Object rules = context.get("rules");
            if (rules instanceof Map<?, ?> rulesMap) {
                rulesMap.forEach((k, v) -> sb.append("   • ").append(v).append("\n"));
            }
            sb.append("\n");

            // Variable syntax
            sb.append("🔤 VARIABLE SYNTAX\n");
            Object varSyntax = context.get("variable_syntax");
            if (varSyntax instanceof Map<?, ?> syntaxMap) {
                syntaxMap.forEach((k, v) -> sb.append("   ").append(k).append(": ").append(v).append("\n"));
            }
            sb.append("\n");

            // Available actions
            sb.append("⚡ AVAILABLE ACTIONS\n");
            Object actions = context.get("actions");
            if (actions instanceof Map<?, ?> actionsMap) {
                actionsMap.forEach((k, v) -> sb.append("   • ").append(k).append(": ").append(v).append("\n"));
            }
            sb.append("\n");

            // Phase and NEXT
            Object phase = context.get("phase");
            Object next = context.get("NEXT");
            sb.append("🎯 NEXT STEP (Phase: ").append(phase != null ? phase : "UNKNOWN").append(")\n");
            sb.append("   ").append(next != null ? next : "Continue building the workflow").append("\n");
            sb.append("\n");

            // Help reference
            sb.append("📚 HELP: workflow(action='help', topics=['webhook', 'agent', 'decision']) for params details\n");
            sb.append("📚 INTERFACE: interface(action='help') for interface template creation guide\n");
        }

        sb.append("═══════════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    /**
     * Helper to append a plan section (triggers, mcps, etc.) to the prompt.
     */
    @SuppressWarnings("unchecked")
    private void appendPlanSection(StringBuilder sb, String sectionName, Object sectionData) {
        if (sectionData instanceof List<?> list && !list.isEmpty()) {
            sb.append("   ").append(sectionName).append(":\n");
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    if (sectionName.equals("edges")) {
                        // Edge format: from → to
                        sb.append("      - ").append(map.get("from")).append(" → ").append(map.get("to")).append("\n");
                    } else if (sectionName.equals("interfaces")) {
                        // Interface format: label [id=uuid, variable_mapping={...}, action_mapping={...}]
                        Object label = map.get("label");
                        Object id = map.get("id");
                        sb.append("      - ").append(label != null ? label : "(no label)");
                        if (id != null) {
                            sb.append(" [id=").append(id).append("]");
                        }
                        Object varMapping = map.get("variableMapping");
                        if (varMapping instanceof Map<?, ?> vm && !vm.isEmpty()) {
                            sb.append(" variable_mapping=").append(vm);
                        }
                        Object actMapping = map.get("actionMapping");
                        if (actMapping instanceof Map<?, ?> am && !am.isEmpty()) {
                            sb.append(" action_mapping=").append(am);
                        }
                        sb.append("\n");
                    } else {
                        // Node format: label (type if available)
                        Object label = map.get("label");
                        Object type = map.get("type");
                        sb.append("      - ").append(label != null ? label : "(no label)");
                        if (type != null) {
                            sb.append(" (").append(type).append(")");
                        }
                        sb.append("\n");
                    }
                }
            }
        }
    }

    private boolean hasCustomPrompt() {
        return customSystemPrompt != null && !customSystemPrompt.isBlank();
    }

    private List<ToolDefinition> getCoreTools(boolean isNewConversation) {
        if (coreToolsProvider == null) {
            log.warn("CoreToolsProvider not available, using auto-discover only");
            return List.of();
        }

        List<ToolDefinition> tools = coreToolsProvider.getCoreTools(isNewConversation);
        if (tools.isEmpty()) {
            log.warn("No core tools available, using auto-discover only");
        }
        return tools;
    }

    private void logTools(List<ToolDefinition> tools) {
        if (tools.isEmpty()) return;

        log.info("=== TOOLS PASSED TO AGENT ({} total) ===", tools.size());
        tools.stream().limit(5).forEach(t -> {
            int paramCount = t.parameters() != null ? t.parameters().size() : 0;
            log.info("  Tool: {} ({} params)", t.name(), paramCount);
        });
        if (tools.size() > 5) {
            log.info("  ... and {} more tools", tools.size() - 5);
        }
        log.info("========================================");
    }

    /**
     * Stage 4a wiring entry point. Looks up the model tier from the cached
     * platform catalog, routes to {@link ModelTierMapper}, and on
     * {@link SchemaMode#SLIM} runs each tool through {@link SchemaSlimmer}
     * unless {@link SchemaSlimExclusionPolicy} pins it to FULL.
     *
     * <p>Per-tool exclusion only: the whole-tool check
     * ({@link SchemaSlimExclusionPolicy#isToolAlwaysFull}) is sufficient here
     * because the tools-prefix builder sees one definition per tool name,
     * not per (tool, action) pair. Per-action exclusions live deeper in the
     * help/execution path.
     *
     * <p><b>Canonical modelId.</b> The catalog lookup normalises the caller's
     * modelId to the canonical form registered in {@code AvailableModel} so
     * {@link ModelTierMapper}'s case-sensitive override map (e.g.
     * {@code glm-5-turbo → FULL}) still matches when the request carries a
     * differently-cased variant. If the catalog has no entry we pass the
     * caller's modelId through untouched so the unknown-model default fires.
     *
     * <p><b>Scope.</b> This slims only the initial tools prefix built here.
     * Tools auto-discovered mid-turn via {@link
     * com.apimarketplace.agent.loop.AgentLoopContext#autoDiscoverTools()}
     * bypass this stage - acceptable because the initial prefix is where the
     * cost blow-up lives and the discovered set is typically &lt; 3 tools.
     */
    List<ToolDefinition> applyJitSchemaSlim(List<ToolDefinition> tools, String provider, String modelId,
                                             String conversationId) {
        if (tools == null || tools.isEmpty()) return tools;
        // Strict (provider, modelId) lookup first. On miss - common when the
        // caller supplies a mis-mapped provider for a bridge-only model - fall
        // back to modelId-only so the tier resolution still succeeds. Tier is a
        // platform-wide capability label and is provider-agnostic, so this
        // relaxation is safe and only affects schema-slim routing (never
        // affects provider selection, which is decided upstream).
        AvailableModel catalogEntry = lookupCatalogModel(provider, modelId);
        if (catalogEntry == null) {
            catalogEntry = agentConfigProvider.findAvailableModelByModelId(modelId);
            if (catalogEntry != null) {
                // Visibility into the mis-mapped-provider rate. DEBUG so it
                // stays out of the hot path but ops can flip the level when
                // chasing a tier-resolution miss in prod.
                log.debug("[JIT] strict (provider={}, model={}) miss; modelId-only fallback hit "
                        + "catalog provider={} tier={}",
                        provider, modelId, catalogEntry.provider(), catalogEntry.tier());
            }
        }
        String canonicalModelId = (catalogEntry != null) ? catalogEntry.modelId() : modelId;
        String tier = (catalogEntry != null) ? catalogEntry.tier() : null;
        SchemaMode mode = modelTierMapper.resolve(provider, canonicalModelId, tier);
        if (mode != SchemaMode.SLIM) {
            log.info("[JIT] provider={} model={} tier={} → FULL ({} tools unchanged)",
                    provider, canonicalModelId, tier, tools.size());
            return tools;
        }
        int currentTurn = resolveCurrentTurn(conversationId);
        List<ToolDefinition> result = new ArrayList<>(tools.size());
        int slimmed = 0;
        int promotedByHelp = 0;
        for (ToolDefinition tool : tools) {
            if (schemaSlimExclusionPolicy.isToolAlwaysFull(tool.name())) {
                result.add(tool);
                continue;
            }
            if (conversationId != null
                    && helpSeenRegistry.isFresh(conversationId, tool.name(), "help", currentTurn)) {
                // LLM has a fresh help response for this tool within the HOT/WARM
                // budget - serving SLIM here would force a redundant help call
                // and waste the turns-old context we just built.
                result.add(tool);
                promotedByHelp++;
                continue;
            }
            result.add(SchemaSlimmer.minimize(tool));
            slimmed++;
        }
        log.info("[JIT] provider={} model={} tier={} turn={} → SLIM ({}/{} tools slimmed, {} pinned FULL, {} promoted by help-seen)",
                provider, canonicalModelId, tier, currentTurn,
                slimmed, tools.size(), tools.size() - slimmed - promotedByHelp, promotedByHelp);
        return result;
    }

    /**
     * Current turn anchor for {@link HelpSeenRegistry#isFresh} - the count of
     * {@link com.apimarketplace.conversation.entity.Message.MessageRole#USER}
     * messages in the conversation. Each user prompt advances the turn; the
     * freshness gate measures {@code currentTurn - lastSeenTurn <= budget}.
     *
     * <p>Returns {@code 0} on null/blank conversation id or DB failure: the
     * gate then decays to "no fresh entry" and the caller falls through to
     * the regular slim path, which is the safe default.
     */
    private int resolveCurrentTurn(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return 0;
        try {
            long userTurns = conversationMessageRepository.countByConversationIdAndRole(
                    conversationId,
                    com.apimarketplace.conversation.entity.Message.MessageRole.USER);
            return (int) Math.min(Integer.MAX_VALUE, userTurns);
        } catch (Exception e) {
            log.debug("[JIT] turn count failed for conv={}: {}", conversationId, e.getMessage());
            return 0;
        }
    }

    /**
     * Look up the {@link AvailableModel} entry for a (provider, modelId) pair
     * against the cached platform catalog. Match is case-insensitive on both
     * provider and modelId so a mixed-case request modelId still canonicalises
     * to the registered form (which is what {@link ModelTierMapper}'s
     * override map keys on, case-sensitively).
     *
     * <p>Catalog misses return {@code null} so the caller can fall back to
     * the unknown-model default. RPC / lookup failures also return null -
     * slimming must never block the conversation.
     */
    private AvailableModel lookupCatalogModel(String provider, String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        try {
            return agentConfigProvider.getAvailableModels().stream()
                    .filter(m -> modelId.equalsIgnoreCase(m.modelId())
                            && (provider == null || provider.equalsIgnoreCase(m.provider())))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.debug("[JIT] catalog lookup failed for {}/{}: {}", provider, modelId, e.getMessage());
            return null;
        }
    }

    // ==================== Skills Tree Prompt ====================

    /**
     * Stable ID comparator - treats null as greater (pushed last). Used to make
     * the rendered [SKILLS] block byte-identical across JVM restarts / pods, so
     * Anthropic prompt caching can match the prefix across the horizontal fleet.
     */
    private static final Comparator<String> NULL_LAST_ID =
            Comparator.nullsLast(Comparator.naturalOrder());

    /**
     * Build a tree-formatted prompt listing the agent's available skills.
     * Renders folder hierarchy with skills nested under their folders.
     *
     * <p>Output is byte-stable: siblings (folders + skills sharing a parent) are
     * sorted by id before rendering. {@link AgentSkillsSummary} is fetched from
     * the source-of-truth store and returned in whatever order Hibernate/JPA
     * produces; without sorting, the [SKILLS] block text varies per-pod and
     * breaks the Anthropic cache prefix on every turn.
     */
    String buildSkillsTreePrompt(AgentSkillsSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("[SKILLS]\n");
        sb.append("You have the following skills. To activate a skill and load its full instructions, call: skill(action='get', skill_id='<id>')\n\n");

        List<FolderSummary> sortedFolders = new ArrayList<>(summary.folders());
        sortedFolders.sort(Comparator.comparing(FolderSummary::id, NULL_LAST_ID));

        List<SkillSummary> sortedSkills = new ArrayList<>(summary.skills());
        sortedSkills.sort(Comparator.comparing(SkillSummary::id, NULL_LAST_ID));

        Map<String, List<FolderSummary>> foldersByParent = new HashMap<>();
        for (FolderSummary folder : sortedFolders) {
            String parentKey = folder.parentId() != null ? folder.parentId() : "__root__";
            foldersByParent.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(folder);
        }

        Map<String, List<SkillSummary>> skillsByFolder = new HashMap<>();
        for (SkillSummary skill : sortedSkills) {
            String folderKey = skill.folderId() != null ? skill.folderId() : "__root__";
            skillsByFolder.computeIfAbsent(folderKey, k -> new ArrayList<>()).add(skill);
        }

        renderFolder(sb, "__root__", 0, foldersByParent, skillsByFolder);

        return sb.toString().stripTrailing();
    }

    private void renderFolder(StringBuilder sb, String folderId, int indent,
                              Map<String, List<FolderSummary>> foldersByParent,
                              Map<String, List<SkillSummary>> skillsByFolder) {
        String prefix = "  ".repeat(indent);

        // Render subfolders first
        List<FolderSummary> subfolders = foldersByParent.getOrDefault(folderId, List.of());
        for (FolderSummary folder : subfolders) {
            sb.append(prefix).append(folder.name()).append("/ [folder:").append(folder.id()).append("]\n");
            renderFolder(sb, folder.id(), indent + 1, foldersByParent, skillsByFolder);
        }

        // Render skills in this folder
        List<SkillSummary> skills = skillsByFolder.getOrDefault(folderId, List.of());
        for (SkillSummary skill : skills) {
            sb.append(prefix).append("- ").append(skill.name());
            sb.append(" [").append(skill.id()).append("]");
            if (skill.description() != null && !skill.description().isBlank()) {
                sb.append(" - ").append(skill.description());
            }
            sb.append("\n");
        }
    }

    // ==================== ToolsConfig Restriction Helpers ====================

    private static final Set<String> WORKFLOW_TOOL_NAMES = Set.of("workflow");

    private static final Set<String> APPLICATION_TOOL_NAMES = Set.of("application");
    private static final Set<String> TABLE_TOOL_NAMES = Set.of("table");
    private static final Set<String> INTERFACE_TOOL_NAMES = Set.of("interface");
    private static final Set<String> AGENT_TOOL_NAMES = Set.of("agent");
    private static final Set<String> WEB_SEARCH_TOOL_NAMES = Set.of("web_search");

    /**
     * Filter tools list based on toolsConfig restrictions.
     * - workflows=none: remove workflow tools from the LLM tool list
     * - Catalog tools (catalog) are kept but enforced at execution time.
     */
    private List<ToolDefinition> applyToolsConfigFiltering(List<ToolDefinition> tools, ToolsConfig tc) {
        List<ToolDefinition> filtered = new ArrayList<>(tools);

        // Remove workflow tools when no workflows are allowed
        if (tc.isWorkflowsNone()) {
            filtered = filtered.stream()
                .filter(t -> !WORKFLOW_TOOL_NAMES.contains(t.name()))
                .collect(Collectors.toList());
            log.info("ToolsConfig: workflows=none, removed {} workflow tools",
                tools.size() - filtered.size());
        }

        // Remove application tools when no applications are allowed
        if (tc.isApplicationsNone()) {
            int before = filtered.size();
            filtered = filtered.stream()
                .filter(t -> !APPLICATION_TOOL_NAMES.contains(t.name()))
                .collect(Collectors.toList());
            log.info("ToolsConfig: applications=none, removed {} application tools",
                before - filtered.size());
        }

        // Remove table tools when no tables are allowed
        if (tc.isTablesNone()) {
            int before = filtered.size();
            filtered = filtered.stream()
                .filter(t -> !TABLE_TOOL_NAMES.contains(t.name()))
                .collect(Collectors.toList());
            log.info("ToolsConfig: tables=none, removed {} table tools",
                before - filtered.size());
        }

        // Remove interface tools when no interfaces are allowed
        if (tc.isInterfacesNone()) {
            int before = filtered.size();
            filtered = filtered.stream()
                .filter(t -> !INTERFACE_TOOL_NAMES.contains(t.name()))
                .collect(Collectors.toList());
            log.info("ToolsConfig: interfaces=none, removed {} interface tools",
                before - filtered.size());
        }

        // Remove agent tools when no agents are allowed
        if (tc.isAgentsNone()) {
            int before = filtered.size();
            filtered = filtered.stream()
                .filter(t -> !AGENT_TOOL_NAMES.contains(t.name()))
                .collect(Collectors.toList());
            log.info("ToolsConfig: agents=none, removed {} agent tools",
                before - filtered.size());
        }

        // Remove web_search tool when web search is disabled
        if (tc.isWebSearchDisabled()) {
            int before = filtered.size();
            filtered = filtered.stream()
                .filter(t -> !WEB_SEARCH_TOOL_NAMES.contains(t.name()))
                .collect(Collectors.toList());
            log.info("ToolsConfig: webSearch=false, removed {} web search tools",
                before - filtered.size());
        }

        return filtered;
    }

    /**
     * Pass restriction data via credentials map for enforcement at execution time.
     */
    private void applyToolsConfigCredentials(Map<String, Object> credentials, ToolsConfig tc) {
        // Tool restrictions
        if (tc.isToolsNone()) {
            credentials.put("__allowedToolIds__", List.of());
            log.info("ToolsConfig: mode=none, passing empty allowedToolIds");
        } else if (tc.isToolsCustom()) {
            credentials.put("__allowedToolIds__", tc.tools());
            log.info("ToolsConfig: mode=custom, passing {} allowedToolIds", tc.tools().size());
        }

        // Workflow restrictions
        if (tc.isWorkflowsNone()) {
            credentials.put("__allowedWorkflowIds__", List.of());
            log.info("ToolsConfig: workflows=none, passing empty allowedWorkflowIds");
        } else if (tc.isWorkflowsCustom()) {
            credentials.put("__allowedWorkflowIds__", tc.workflows());
            log.info("ToolsConfig: workflows=custom, passing {} allowedWorkflowIds", tc.workflows().size());
        }

        // Application restrictions
        if (tc.isApplicationsNone()) {
            credentials.put("__allowedApplicationIds__", List.of());
            log.info("ToolsConfig: applications=none, passing empty allowedApplicationIds");
        } else if (tc.isApplicationsCustom()) {
            credentials.put("__allowedApplicationIds__", tc.applications());
            log.info("ToolsConfig: applications=custom, passing {} allowedApplicationIds", tc.applications().size());
        }

        // Table restrictions
        if (tc.isTablesNone()) {
            credentials.put("__allowedTableIds__", List.of());
            log.info("ToolsConfig: tables=none, passing empty allowedTableIds");
        } else if (tc.isTablesCustom()) {
            credentials.put("__allowedTableIds__", tc.tables());
            log.info("ToolsConfig: tables=custom, passing {} allowedTableIds", tc.tables().size());
        }

        // Interface restrictions
        if (tc.isInterfacesNone()) {
            credentials.put("__allowedInterfaceIds__", List.of());
            log.info("ToolsConfig: interfaces=none, passing empty allowedInterfaceIds");
        } else if (tc.isInterfacesCustom()) {
            credentials.put("__allowedInterfaceIds__", tc.interfaces());
            log.info("ToolsConfig: interfaces=custom, passing {} allowedInterfaceIds", tc.interfaces().size());
        }

        // Agent restrictions
        if (tc.isAgentsNone()) {
            credentials.put("__allowedAgentIds__", List.of());
            log.info("ToolsConfig: agents=none, passing empty allowedAgentIds");
        } else if (tc.isAgentsCustom()) {
            credentials.put("__allowedAgentIds__", tc.agents());
            log.info("ToolsConfig: agents=custom, passing {} allowedAgentIds", tc.agents().size());
        }

        // File restrictions - opt-in: only a non-empty allow-list scopes the files tool.
        // (Absent/empty = full org-scoped file access, so existing agents are unaffected.)
        if (tc.isFilesScoped()) {
            credentials.put("__allowedFileIds__", tc.files());
            log.info("ToolsConfig: passing {} allowedFileIds", tc.files().size());
        }

        // Per-resource access modes (read/write)
        if (tc.tableAccessMode() != null) credentials.put("__tableAccessMode__", tc.tableAccessMode());
        if (tc.workflowAccessMode() != null) credentials.put("__workflowAccessMode__", tc.workflowAccessMode());
        if (tc.interfaceAccessMode() != null) credentials.put("__interfaceAccessMode__", tc.interfaceAccessMode());
        if (tc.agentAccessMode() != null) credentials.put("__agentAccessMode__", tc.agentAccessMode());
        if (tc.applicationAccessMode() != null) credentials.put("__applicationAccessMode__", tc.applicationAccessMode());
        if (tc.skillAccessMode() != null) credentials.put("__skillAccessMode__", tc.skillAccessMode());
        if (tc.fileAccessMode() != null) credentials.put("__fileAccessMode__", tc.fileAccessMode());
    }

    /**
     * Build system prompt instructions based on toolsConfig restrictions.
     *
     * <p><b>Grant-driven, R/W-decoupled.</b> For each of the 5 internal resource families
     * ({@code workflows}/{@code interfaces}/{@code tables}/{@code applications}/{@code agents})
     * the per-family GRANT sentinel ({@code <family>Grant} ∈ {@code {none|all|custom}}) is the
     * SINGLE authority for what this block emits:
     * <ul>
     *   <li>{@code none} (or absent) → the hard "[X RESTRICTIONS] You do NOT have access" block;</li>
     *   <li>{@code all} → nothing (unrestricted, no block);</li>
     *   <li>{@code custom} → the softer "limited set" note pointing the agent at its
     *       {@code list/search}-scoped view.</li>
     * </ul>
     *
     * <p>The per-resource R/W access mode ({@code <family>AccessMode} ∈ {@code {read|write}})
     * is a SEPARATE axis enforced at the tool layer ({@link com.apimarketplace.agent.config.ToolAccessControl#checkWriteAccess})
     * and deliberately does NOT influence this prompt: read-vs-write applies WITHIN granted
     * access, it never turns a {@code none} grant into access nor an {@code all} grant into a
     * restriction. A BUILDER agent (e.g. App Factory) is durably granted {@code all} on its
     * builder families, so it never sees a contradictory "do NOT have access" line regardless
     * of its write mode.
     */
    private String buildToolsConfigPrompt(ToolsConfig tc) {
        StringBuilder sb = new StringBuilder();

        if (tc.isToolsNone()) {
            sb.append("[TOOL RESTRICTIONS] You do NOT have access to external API tools. ");
            sb.append("Do not attempt to use catalog(action='search') or catalog(action='execute') for external services. ");
            sb.append("Focus on conversation and any built-in tools available to you.\n");
        } else if (tc.isToolsCustom()) {
            sb.append("[TOOL RESTRICTIONS] You have access to a limited set of pre-approved tools only. ");
            sb.append("catalog(action='search') will return ONLY your approved tools. ");
            sb.append("Do not attempt to use tools outside your approved list.\n");
        }

        if (tc.isWorkflowsNone()) {
            sb.append("[WORKFLOW RESTRICTIONS] You do NOT have access to workflows. ");
            sb.append("Do not attempt to use workflow tools.\n");
        } else if (tc.isWorkflowsCustom()) {
            sb.append("[WORKFLOW RESTRICTIONS] You have access to a limited set of workflows only. ");
            sb.append("workflow(action='list') will return ONLY your approved workflows.\n");
        }

        if (tc.isApplicationsNone()) {
            sb.append("[APPLICATION RESTRICTIONS] You do NOT have access to marketplace applications. ");
            sb.append("Do not attempt to use the application tool.\n");
        } else if (tc.isApplicationsCustom()) {
            sb.append("[APPLICATION RESTRICTIONS] You have access to a limited set of marketplace applications only. ");
            sb.append("application(action='search') will return ONLY your approved applications.\n");
        }

        if (tc.isTablesNone()) {
            sb.append("[TABLE RESTRICTIONS] You do NOT have access to tables. ");
            sb.append("Do not attempt to use the table tool.\n");
        } else if (tc.isTablesCustom()) {
            sb.append("[TABLE RESTRICTIONS] You have access to a limited set of tables only. ");
            sb.append("table(action='list') will return ONLY your approved tables.\n");
        }

        if (tc.isInterfacesNone()) {
            sb.append("[INTERFACE RESTRICTIONS] You do NOT have access to interfaces. ");
            sb.append("Do not attempt to use the interface tool.\n");
        } else if (tc.isInterfacesCustom()) {
            sb.append("[INTERFACE RESTRICTIONS] You have access to a limited set of interfaces only. ");
            sb.append("interface(action='list') will return ONLY your approved interfaces.\n");
        }

        if (tc.isAgentsNone()) {
            sb.append("[AGENT RESTRICTIONS] You do NOT have access to sub-agents. ");
            sb.append("Do not attempt to use the agent tool.\n");
        } else if (tc.isAgentsCustom()) {
            sb.append("[AGENT RESTRICTIONS] You have access to a limited set of sub-agents only. ");
            sb.append("agent(action='list') will return ONLY your approved agents.\n");
        }

        return sb.isEmpty() ? null : sb.toString().trim();
    }
}
