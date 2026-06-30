package com.apimarketplace.agent.service.cli;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.dto.cli.*;
import com.apimarketplace.agent.dto.cli.CliSessionResponse.ToolInfo;
import com.apimarketplace.agent.prompt.ConversationToolDefinitions;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.execution.AgentToolsConfigCredentials;
import com.apimarketplace.agent.service.execution.CoreToolsCache;
import com.apimarketplace.agent.service.execution.RemoteToolExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Session-based CLI tool execution service.
 * Claude Code IS the agent - this service only manages sessions and executes tools directly.
 * No LLM loop, no AgentLoopService dependency.
 *
 * Flow: startSession() → executeTool() (N times) → endSession()
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CliAgentService {

    private final CoreToolsCache coreToolsCache;
    private final RemoteToolExecutionService remoteToolExecutionService;
    private final AgentObservabilityService observabilityService;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    /** Module keys derived from DefaultSystemPrompts - single source of truth. */
    private static final Set<String> KNOWN_MODULE_KEYS = DefaultSystemPrompts.ALL_RESOURCE_MODULES.stream()
        .map(DefaultSystemPrompts.PromptModule::key)
        .collect(Collectors.toUnmodifiableSet());

    /** Session TTL: 30 minutes */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    /** In-memory session store with TTL cleanup */
    private final ConcurrentHashMap<String, CliSession> sessions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cli-session-cleanup");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void init() {
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }

    // ==================== Public API ====================

    /**
     * Start a new CLI session. Returns available tools and system prompt.
     *
     * <p>Round-8 audit fix: {@code requireOrgId} fails-fast on null/blank
     * orgId at session start. Without this, a session with {@code organizationId=null}
     * would persist observability rows that violate the V263 NOT NULL constraint -
     * and that violation would be log-and-dropped by the broad catch in
     * {@code recordObservability}. Fail at the entry instead so the caller sees
     * the bug at their own stack, not in a CLI log warning.
     */
    public CliSessionResponse startSession(CliSessionStartRequest request, String tenantId, String organizationId) {
        return startSession(request, tenantId, organizationId, null, null);
    }

    public CliSessionResponse startSession(CliSessionStartRequest request, String tenantId,
                                           String organizationId, String organizationRole) {
        return startSession(request, tenantId, organizationId, organizationRole, null);
    }

    public CliSessionResponse startSession(CliSessionStartRequest request, String tenantId,
                                           String organizationId, String organizationRole, String userRoles) {
        com.apimarketplace.common.web.TenantResolver.requireOrgId(organizationId);

        // Resolve or generate session ID
        String sessionId = request != null && request.sessionId() != null
            ? request.sessionId() : UUID.randomUUID().toString();

        // Resume existing session if it exists
        CliSession existing = sessions.get(sessionId);
        if (existing != null && existing.tenantId.equals(tenantId)) {
            log.info("Resuming CLI session: {}", sessionId);
            existing.lastAccessedAt = Instant.now();
            return buildSessionResponse(existing);
        }

        // Resolve modules
        List<String> enabledModules = request != null ? request.enabledModules() : null;
        Set<String> modules = resolveModules(enabledModules);

        // Build system prompt
        DefaultSystemPrompts.ModularPromptResult promptResult =
            DefaultSystemPrompts.build(modules, false);

        // Get tools - start with core tools from cache
        List<ToolDefinition> tools = new ArrayList<>(coreToolsCache.getCoreTools(promptResult.coreToolNames()));

        // Add conversation tools when conversationId is present (bridge mode).
        // Agent-bound conversations (agentId set) inherit their title from the agent
        // entity at creation time - the LLM must NOT override it, so set_conversation_title
        // is only offered on general chat (no agentId) on the first turn.
        if (request != null && request.conversationId() != null) {
            boolean hasAgent = request.agentId() != null && !request.agentId().isBlank();
            boolean includeTitleTool = Boolean.TRUE.equals(request.isNewConversation()) && !hasAgent;
            tools.addAll(ConversationToolDefinitions.getConversationTools(includeTitleTool));
        }

        // Build credentials
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("conversationId", request != null && request.conversationId() != null
            ? request.conversationId() : sessionId);
        credentials.put("turnId", UUID.randomUUID().toString());
        credentials.put("__agent_depth__", 0);
        // 2026-05-21 prod fix: stamp __orgId__ + __orgRole__ on session credentials
        // so every downstream RemoteToolExecutionService call forwards
        // X-Organization-ID + X-Organization-Role headers to the target tool service
        // (interface, agent, application, datasource, ...). Without this, every
        // tool call from the CLI agent session arrived at the downstream service
        // with no org context → strict-isolation rejected org-scoped reads
        // ("organizationId required after V261") and canAccess deny-list rejected
        // role-aware writes ("Access to this agent is restricted") even for
        // workspace OWNERs. Source of truth: gateway-validated X-Organization-ID
        // header on the inbound /api/agent/cli/session request - resolved by
        // CliAgentController and threaded here.
        if (organizationId != null && !organizationId.isBlank()) {
            credentials.put("__orgId__", organizationId);
        }
        if (organizationRole != null && !organizationRole.isBlank()) {
            credentials.put("__orgRole__", organizationRole);
        }
        // 2026-06-09 fix: stamp the caller's PLATFORM roles so admin-gated tool
        // modules (e.g. SkillCrudModule.callerIsAdmin, AgentHelpModule's
        // admin-only model gating) can read __userRoles__ from the session
        // credentials. The bridge/CLI path bypasses the gateway, so these roles
        // are resolved server-side (CliAgentController → AuthClient.getUserRoles)
        // rather than injected as an X-User-Roles header. Parity with
        // AgentToolsController.applyUserRoles on the remote agent path.
        if (userRoles != null && !userRoles.isBlank()) {
            credentials.put("__userRoles__", userRoles);
        }
        if (request != null && request.streamId() != null) {
            credentials.put("__streamId__", request.streamId());
        }
        // Inject agentId so SubAgentExecutionHandler can build the caller chain
        // for cascade budget reservation/settlement (consumed_from_subagents tracking).
        if (request != null && request.agentId() != null && !request.agentId().isBlank()) {
            credentials.put("__agentId__", request.agentId());
        }
        // Inject executionId so AgentTaskService.claimTask can write the claim log
        // keyed by the same UUID the observability writer will persist as
        // agent_executions.id - closing the race where claim arrives before INSERT.
        if (request != null && request.executionId() != null && !request.executionId().isBlank()) {
            credentials.put("__executionId__", request.executionId());
        }
        // Inject user-authorized tool-action rules so ToolAuthorizationGuard skips the
        // gate on a resume turn (bridge parity with the remote AgentLoopService path).
        if (request != null && request.approvedToolActions() != null
                && !request.approvedToolActions().isEmpty()) {
            credentials.put("__approvedToolActions__", request.approvedToolActions());
        }

        // Store conversation-service callback URL for conversation tool routing
        if (request != null && request.conversationServiceUrl() != null) {
            credentials.put("__toolCallbackUrl__",
                request.conversationServiceUrl() + "/api/internal/conversation/tools/execute");
        }

        // Thread the BOUND agent's resource allow-list (toolsConfig) into the session
        // credentials so the CLI-bridge tool path enforces the SAME scope as the direct-API
        // path (AgentLoopContext fed by AgentContextBuilder.applyToolsConfigCredentials).
        // Without this, a top-level bridge agent ran UNRESTRICTED: CliSessionStartRequest
        // carries no allow-list, so allowedTableIds + the 5 sibling resources + access modes
        // were absent → ToolAccessControl treats null as "no restriction" → an agent limited
        // to e.g. tables:[222] could still read every other table/workflow/interface in its
        // own tenant. RemoteToolExecutionService.forwardCredentials then carries these keys to
        // each downstream tool service (it already reads both the plain and __namespaced__ form).
        applyAgentResourceAllowlist(credentials, request, tenantId, organizationId, organizationRole);

        // Create session
        CliSession session = new CliSession();
        session.sessionId = sessionId;
        session.tenantId = tenantId;
        session.organizationId = organizationId;
        session.model = request != null ? request.model() : null;
        session.startedAt = Instant.now();
        session.lastAccessedAt = Instant.now();
        session.tools = tools;
        session.credentials = credentials;
        session.systemPrompt = promptResult.systemPrompt();
        session.toolResults = new ArrayList<>();

        sessions.put(sessionId, session);
        log.info("Started CLI session: {} (tenant={}, tools={})", sessionId, tenantId, tools.size());

        return buildSessionResponse(session);
    }

    /**
     * Thread the bound agent's {@code toolsConfig} resource scope into the session credentials.
     *
     * <p>Parity with the direct-API path: a session bound to an agent (agentId present) enforces
     * that agent's allow-list; a general chat (no agentId) or an agent with no toolsConfig stays
     * unrestricted - exactly as the conversation path only calls
     * {@code AgentContextBuilder.applyToolsConfigCredentials} when {@code toolsConfig != null}.
     * The agent is loaded in the session's workspace scope so its stored config (not a cross-org
     * row) drives enforcement. {@code AgentToolsConfigCredentials.apply} writes the plain
     * {@code allowed<Resource>Ids} + {@code <resource>AccessMode} keys, which
     * {@code RemoteToolExecutionService.forwardCredentials} forwards to every downstream tool call.
     */
    private void applyAgentResourceAllowlist(Map<String, Object> credentials, CliSessionStartRequest request,
                                             String tenantId, String organizationId, String organizationRole) {
        if (request == null || request.agentId() == null || request.agentId().isBlank()) {
            return; // general chat / no bound agent → unrestricted (matches the direct-API path)
        }
        UUID agentId;
        try {
            agentId = UUID.fromString(request.agentId());
        } catch (IllegalArgumentException e) {
            log.warn("CLI session agentId is not a UUID, leaving session unrestricted: {}", request.agentId());
            return;
        }
        Map<String, Object> toolsConfig = agentService.getAgent(agentId, tenantId, organizationId, organizationRole)
            .map(AgentEntity::getToolsConfig)
            .orElse(null);
        if (toolsConfig != null) {
            AgentToolsConfigCredentials.apply(credentials, toolsConfig);
            // The catalog `tools` axis (top-level mode) is parent-inherited for sub-agents but must be
            // resolved from the bound agent for a parent-less CLI session, else a mode=custom agent
            // could execute ANY catalog tool on the bridge (the same bypass, on the catalog axis).
            AgentToolsConfigCredentials.applyCatalogToolsMode(credentials, toolsConfig);
            log.info("CLI session scoped to agent {} toolsConfig resource allow-list", agentId);
        }
    }

    /**
     * Execute a single tool within an existing session.
     */
    public CliToolResponse executeTool(CliToolRequest request, String tenantId) {
        long startTime = System.currentTimeMillis();

        // Find session
        CliSession session = sessions.get(request.sessionId());
        if (session == null) {
            return new CliToolResponse(false, null,
                "Session not found: " + request.sessionId(), 0, null);
        }
        if (!session.tenantId.equals(tenantId)) {
            return new CliToolResponse(false, null,
                "Session belongs to a different tenant", 0, null);
        }

        session.lastAccessedAt = Instant.now();

        // Find tool definition
        ToolDefinition toolDef = session.tools.stream()
            .filter(td -> td.name().equals(request.tool()))
            .findFirst()
            .orElse(null);

        if (toolDef == null) {
            return new CliToolResponse(false, null,
                "Tool not found: " + request.tool() + ". Available: " +
                    session.tools.stream().map(ToolDefinition::name).toList(),
                System.currentTimeMillis() - startTime, null);
        }

        // Build ToolCall
        String toolCallId = "cli_" + UUID.randomUUID().toString().substring(0, 8);
        ToolCall toolCall = ToolCall.builder()
            .id(toolCallId)
            .toolName(request.tool())
            .arguments(request.params())
            .build();

        // Execute via RemoteToolExecutionService (goes to orchestrator)
        try {
            ToolResult result = remoteToolExecutionService.executeTool(
                toolCall, toolDef, tenantId, session.credentials);

            long duration = System.currentTimeMillis() - startTime;

            // Record in session
            session.toolResults.add(result);
            session.toolCallCount++;

            log.info("CLI tool executed: {} (session={}, success={}, {}ms)",
                request.tool(), request.sessionId(), result.success(), duration);

            return new CliToolResponse(
                result.success(),
                result.content(),
                result.error(),
                duration,
                result.metadata()
            );

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("CLI tool execution failed: {} (session={}): {}",
                request.tool(), request.sessionId(), e.getMessage(), e);

            // Record failure in session
            ToolResult failResult = ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Execution error: " + e.getMessage())
                .durationMs(duration)
                .build();
            session.toolResults.add(failResult);
            session.toolCallCount++;

            return new CliToolResponse(false, null,
                "Execution error: " + e.getMessage(), duration, null);
        }
    }

    /**
     * End a CLI session. Records observability and cleans up.
     */
    public CliSessionEndResponse endSession(String sessionId, String tenantId) {
        CliSession session = sessions.remove(sessionId);
        if (session == null) {
            return new CliSessionEndResponse(sessionId, 0, 0, List.of());
        }
        if (!session.tenantId.equals(tenantId)) {
            sessions.put(sessionId, session); // put back if wrong tenant
            return new CliSessionEndResponse(sessionId, 0, 0, List.of());
        }

        long totalDuration = java.time.Duration.between(session.startedAt, Instant.now()).toMillis();

        // Collect unique tools used
        Set<String> uniqueTools = new LinkedHashSet<>();
        for (ToolResult tr : session.toolResults) {
            if (tr.toolCall() != null && tr.toolCall().toolName() != null) {
                uniqueTools.add(tr.toolCall().toolName());
            }
        }

        // Record observability
        recordObservability(session, totalDuration);

        log.info("Ended CLI session: {} (tools={}, calls={}, {}ms)",
            sessionId, uniqueTools, session.toolCallCount, totalDuration);

        return new CliSessionEndResponse(
            sessionId,
            session.toolCallCount,
            totalDuration,
            List.copyOf(uniqueTools)
        );
    }

    // ==================== Helpers ====================

    private Set<String> resolveModules(List<String> enabledModules) {
        // null = all modules enabled (unrestricted - matches DefaultSystemPrompts.build(null) behavior)
        if (enabledModules == null) {
            return new LinkedHashSet<>(KNOWN_MODULE_KEYS);
        }
        // An EMPTY list = NO modules = 0 tools (a mode=off / tool-less judge agent), matching the
        // canonical DefaultSystemPrompts.build([]) contract (null=all, []=none). Unknown keys are
        // dropped; an all-unknown (or empty) input therefore yields an empty set = no core tools.
        Set<String> resolved = new LinkedHashSet<>();
        for (String key : enabledModules) {
            if (KNOWN_MODULE_KEYS.contains(key)) {
                resolved.add(key);
            } else {
                log.warn("Unknown module key '{}', ignoring", key);
            }
        }
        return resolved;
    }

    private CliSessionResponse buildSessionResponse(CliSession session) {
        List<ToolInfo> toolInfos = session.tools.stream()
            .map(td -> new ToolInfo(td.name(), td.description(), buildInputSchema(td)))
            .toList();
        List<String> toolNames = session.tools.stream()
            .map(ToolDefinition::name)
            .toList();
        return new CliSessionResponse(session.sessionId, session.systemPrompt, toolInfos, toolNames);
    }

    /**
     * Build a JSON Schema "inputSchema" from ToolDefinition parameters.
     * This allows MCP clients to register tools with proper parameter definitions.
     */
    private Map<String, Object> buildInputSchema(ToolDefinition td) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        if (td.parameters() == null || td.parameters().isEmpty()) {
            schema.put("properties", Map.of());
            return schema;
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParameter param : td.parameters()) {
            Map<String, Object> prop = buildParameterSchema(param);
            properties.put(param.name(), prop);
            if (param.required()) {
                required.add(param.name());
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private Map<String, Object> buildParameterSchema(ToolParameter param) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", param.type() != null ? param.type() : "string");
        if (param.description() != null) {
            prop.put("description", param.description());
        }
        if (param.enumValues() != null && !param.enumValues().isEmpty()) {
            prop.put("enum", param.enumValues());
        }
        if (param.defaultValue() != null) {
            prop.put("default", param.defaultValue());
        }
        if (param.minLength() != null) prop.put("minLength", param.minLength());
        if (param.maxLength() != null) prop.put("maxLength", param.maxLength());
        if (param.minimum() != null) prop.put("minimum", param.minimum());
        if (param.maximum() != null) prop.put("maximum", param.maximum());
        if (param.pattern() != null) prop.put("pattern", param.pattern());

        // Nested object properties
        if (param.properties() != null && !param.properties().isEmpty()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (var entry : param.properties().entrySet()) {
                nested.put(entry.getKey(), buildParameterSchema(entry.getValue()));
            }
            prop.put("properties", nested);
        }
        return prop;
    }

    private void recordObservability(CliSession session, long totalDuration) {
        try {
            AgentObservabilityRequest request = new AgentObservabilityRequest();
            request.setTenantId(session.tenantId);
            // PR20 - workspace identity captured at session start.
            request.setOrganizationId(session.organizationId);
            request.setAgentType("CLI");
            request.setNodeId("cli:" + session.sessionId);
            request.setProvider("external"); // Claude Code is the LLM
            request.setModel(session.model != null ? session.model : "claude-code");
            request.setStatus(session.toolResults.stream().allMatch(ToolResult::success)
                ? "COMPLETED" : "PARTIAL_SUCCESS");
            request.setDurationMs(totalDuration);
            request.setIterationCount(session.toolCallCount);
            request.setTotalToolCalls(session.toolCallCount);

            // Unique tool count
            Set<String> uniqueTools = new HashSet<>();
            for (ToolResult tr : session.toolResults) {
                if (tr.toolCall() != null && tr.toolCall().toolName() != null) {
                    uniqueTools.add(tr.toolCall().toolName());
                }
            }
            request.setUniqueToolCount(uniqueTools.size());

            // Tool call details
            if (!session.toolResults.isEmpty()) {
                var toolCalls = new ArrayList<AgentObservabilityRequest.ToolCallData>();
                int seq = 0;
                for (ToolResult tr : session.toolResults) {
                    var tc = new AgentObservabilityRequest.ToolCallData();
                    tc.setSequenceNumber(seq++);
                    if (tr.toolCall() != null) {
                        tc.setToolCallId(tr.toolCall().id());
                        tc.setToolName(tr.toolCall().toolName());
                        tc.setArguments(tr.toolCall().arguments());
                    }
                    tc.setSuccess(tr.success());
                    tc.setResult(tr.content());
                    tc.setDurationMs(tr.durationMs() != null ? tr.durationMs() : 0L);
                    toolCalls.add(tc);
                }
                request.setToolCalls(toolCalls);
            }

            observabilityService.recordFromRequest(request);

        } catch (Exception e) {
            log.warn("Failed to record CLI session observability: {}", e.getMessage());
        }
    }

    private void cleanupExpiredSessions() {
        Instant cutoff = Instant.now().minusMillis(SESSION_TTL_MS);
        int removed = 0;
        for (var entry : sessions.entrySet()) {
            if (entry.getValue().lastAccessedAt.isBefore(cutoff)) {
                final CliSession expired = sessions.remove(entry.getKey());
                if (expired != null) {
                    long duration = java.time.Duration.between(expired.startedAt, Instant.now()).toMillis();
                    // Audit round-6 fix: this runs on the cli-session-cleanup daemon
                    // thread (single-thread ScheduledExecutorService, no servlet
                    // request bound). recordObservability internally calls
                    // creditClient.persistRejection / consumeCreditsAsync which
                    // need the org context to land on the V261 NOT NULL
                    // credit_consumption_dead_letter row. Wrap the per-session
                    // call so each expired session restores its own org binding.
                    com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
                            expired.organizationId,
                            () -> recordObservability(expired, duration));
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("Cleaned up {} expired CLI sessions", removed);
        }
    }

    // ==================== Session State ====================

    static class CliSession {
        String sessionId;
        String tenantId;
        /** PR20 - workspace identity at session start. NULL = personal scope. */
        String organizationId;
        String model;
        Instant startedAt;
        Instant lastAccessedAt;
        List<ToolDefinition> tools;
        Map<String, Object> credentials;
        String systemPrompt;
        List<ToolResult> toolResults;
        int toolCallCount;
    }
}
