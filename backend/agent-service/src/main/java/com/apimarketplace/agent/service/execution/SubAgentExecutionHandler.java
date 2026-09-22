package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.budget.AgentBudgetGuard;
import com.apimarketplace.agent.service.budget.BudgetReservationService;
import com.apimarketplace.agent.service.budget.BudgetResolver;
import com.apimarketplace.agent.service.budget.BudgetState;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.apimarketplace.agent.service.budget.InsufficientBudgetException;
import com.apimarketplace.agent.service.budget.ModelCostCalculator;
import com.apimarketplace.agent.service.budget.TenantBudgetGuard;
import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.apimarketplace.agent.service.credentials.CredentialsCallerChain;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import java.util.stream.Collectors;

/**
 * Handles agent(action='execute') tool calls locally within agent-service,
 * eliminating the round-trip to orchestrator.
 *
 * Replicates the logic from orchestrator's AgentExecuteModule but:
 * - Loads agent config from local DB (AgentService)
 * - Gets tools from CoreToolsCache (fetched from orchestrator at startup)
 * - Calls AgentLoopService.execute() directly (no HTTP round-trip)
 * - Sub-agent tool calls still route to orchestrator via RemoteToolExecutionService
 *
 * Safety guards (same as AgentExecuteModule):
 * - Depth tracking (no hard limit - cycles prevented by toolsConfig)
 * - Rate limit: 3 executions per turn
 * - Timeout: 600s default, 7200s max, 10s min
 * - Response truncation: 50000 chars max (full payload always recoverable via get_tool_result)
 * - Access control: agentAccessMode + allowedAgentIds from credentials
 */
@Slf4j
@Component
public class SubAgentExecutionHandler {

    /** Same key AgentHelpModule and AgentCrudModule read; the gateway injects it upstream. */
    private static final String CRED_USER_ROLES = "__userRoles__";

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int MIN_TIMEOUT_SECONDS = 10;
    private static final int MAX_TIMEOUT_SECONDS = 7200;
    private static final int MAX_RESPONSE_LENGTH = 50000;
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final int DEFAULT_MEMORY_LIMIT = 20;

    private final AgentService agentService;
    private final AgentLoopService agentLoopService;
    private final CoreToolsCache coreToolsCache;
    private final ConversationClient conversationServiceClient;
    private final AgentObservabilityService observabilityService;
    private final ConversationRedisStreamingCallback conversationRedisStreamingCallback;
    private final StringRedisTemplate redisTemplate;
    private final com.apimarketplace.common.event.EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final BudgetResolver budgetResolver;
    private final BudgetReservationService budgetReservationService;
    private final CreditConsumptionClient creditConsumptionClient;
    private final com.apimarketplace.agent.service.AgentTaskService agentTaskService;
    private final GuardChainFactory guardChainFactory;
    private final AgentActivityPublisher agentActivityPublisher;
    private final AgentDefaultsConfig agentDefaults;

    @Autowired(required = false)
    private SubAgentBridgeClient bridgeClient;

    /**
     * Long-term memory block for the delegated agent. This handler assembles its
     * own system prompt instead of going through
     * {@code AgentRemoteExecutionService}, so it is the second - and only other -
     * place the block has to be appended. Optional for the same reason as the
     * gates below: unit tests construct this handler without wiring it.
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.memory.MemoryPromptSection memoryPromptSection;

    /**
     * CE→cloud web-search relay gate. Per-tenant runtime filter: with the local
     * websearch engine disabled, sub-agents only see web_search when the tenant
     * is cloud-linked with the CLOUD source. Optional so unit tests that don't
     * wire it still construct the handler; null ⇒ no filtering (cloud behavior).
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.cloud.CeWebSearchRelayGate webSearchRelayGate;

    /**
     * Catalog lookup for the per-model default reasoning effort (bridge sub-agents).
     * Optional so unit tests that don't wire it still construct the handler;
     * null ⇒ fall back to the agent-level effort only.
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.service.ModelCatalogService modelCatalog;

    /**
     * Resolves a child's key route when it cannot inherit the parent's (different provider,
     * parent on its own key). Optional so the positional constructor stays test-friendly;
     * absent, such a child runs unpinned (user-first by tenant, the pre-pin behaviour).
     */
    @Autowired(required = false)
    private KeyRouteResolver keyRouteResolver;

    /**
     * Whose key this child runs on, for the provider it actually executes on.
     *
     * <p>A CLI bridge holds no API key: the child is pinned {@code PLATFORM} without any lookup
     * and WITHOUT touching the stamp it carries, so a bridge hop is transparent for routing.
     * The execution-link bridge-failure fallback then re-reads the parent's original stamp for
     * the billed pair, and anything the bridge child spawns inherits from the parent, not from
     * a bridge pin that means nothing for a direct-API provider.
     *
     * <p>Otherwise: inherited from the parent's stamp when the provider is the same, else
     * resolved fresh for the child's provider (a pin is per (tenant, provider)). That
     * covers a parent on its own OpenAI key spawning an Anthropic child, AND a parent that was
     * never pinned at all (a chat that ran on a CLI bridge, which never builds a context): its
     * direct-API children take their own pin here, so no production path runs unpinned while a
     * resolver is wired. The decision is re-stamped into the child's credentials so grandchildren
     * inherit THIS child's pin; a null decision (no resolver) leaves the parent's stamp alone. See
     * {@link KeyRoute#inheritFor}.
     */
    private KeyRoute childKeyRoute(Map<String, Object> credentials, String tenantId, String provider) {
        if (SubAgentBridgeClient.isBridgeProvider(provider)) {
            return KeyRoute.PLATFORM;
        }
        KeyRoute route = KeyRoute.inheritFor(credentials, provider);
        if (route == null && keyRouteResolver != null) {
            route = keyRouteResolver.resolve(tenantId, provider);
        }
        if (route != null) {
            KeyRoute.stamp(credentials, route, provider);
        }
        return route;
    }

    /**
     * Model execution links: a delegated sub-agent's billed pair may have to run on
     * another target, exactly like a top-level agent run. The router itself is an
     * unconditional bean (it answers "no route" in CE, where the link store is off), so
     * this is null only in a unit test that constructs the handler directly - which then
     * runs the billed pair verbatim.
     */
    @Autowired(required = false)
    private ExecutionLinkRouter executionLinkRouter;

    /**
     * Optional, exactly like {@code BridgeLoopDispatcher}'s: the bean is wired in agent-service,
     * and optional injection keeps test slices and bridge-less deployments green. Absent means no
     * check, which is the behaviour this branch had for its whole life.
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.bridge.BridgeAccessGuard bridgeAccessGuard;

    /** Visible for tests (mirrors BridgeLoopDispatcher / AgentHelpModule). */
    public void setBridgeAccessGuard(com.apimarketplace.agent.bridge.BridgeAccessGuard guard) {
        this.bridgeAccessGuard = guard;
    }

    /**
     * Optional Prometheus metrics sink, field-injected for the same reason as the other
     * optional collaborators above. Only consulted by the execution-link bridge-failure
     * fallback below, to keep a silent-by-design recovery visible to operators.
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.metrics.AgentPrometheusMetrics prometheusMetrics;

    /**
     * Activity source reported for link resolution. It matches no surface scope, so a
     * sub-agent can only ever be routed by an {@code ALL} link - the same wildcard-only
     * treatment the browser agent and single completions get. It is the string the fleet
     * activity feed already uses for these runs.
     */
    static final String ACTIVITY_SOURCE = "SUB_AGENT";

    /** Redis key prefix for per-turn rate limiting (distributed across instances). */
    private static final String RATE_LIMIT_PREFIX = "agent:rate-limit:turn:";
    private static final java.time.Duration RATE_LIMIT_TTL = java.time.Duration.ofMinutes(30);

    public SubAgentExecutionHandler(AgentService agentService,
                                     AgentLoopService agentLoopService,
                                     CoreToolsCache coreToolsCache,
                                     ConversationClient conversationServiceClient,
                                     AgentObservabilityService observabilityService,
                                     ConversationRedisStreamingCallback conversationRedisStreamingCallback,
                                     StringRedisTemplate redisTemplate,
                                     com.apimarketplace.common.event.EventBus eventBus,
                                     ObjectMapper objectMapper,
                                     BudgetResolver budgetResolver,
                                     BudgetReservationService budgetReservationService,
                                     CreditConsumptionClient creditConsumptionClient,
                                     com.apimarketplace.agent.service.AgentTaskService agentTaskService,
                                     GuardChainFactory guardChainFactory,
                                     AgentActivityPublisher agentActivityPublisher,
                                     AgentDefaultsConfig agentDefaults) {
        this.agentService = agentService;
        this.agentLoopService = agentLoopService;
        this.coreToolsCache = coreToolsCache;
        this.conversationServiceClient = conversationServiceClient;
        this.observabilityService = observabilityService;
        this.conversationRedisStreamingCallback = conversationRedisStreamingCallback;
        this.redisTemplate = redisTemplate;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.budgetResolver = budgetResolver;
        this.budgetReservationService = budgetReservationService;
        this.creditConsumptionClient = creditConsumptionClient;
        this.agentTaskService = agentTaskService;
        this.guardChainFactory = guardChainFactory;
        this.agentActivityPublisher = agentActivityPublisher;
        this.agentDefaults = agentDefaults;
    }

    /**
     * Resolves the per-turn sub-agent execution cap via
     * {@link com.apimarketplace.agent.config.GuardOverrides#resolve}: caller-agent
     * override (V100 column, read via {@code __agentId__}) → conversation-scope chatConfig
     * (via {@code __chatMaxPerResourcePerTurn__} credential) → YAML default. The same
     * cap is shared uniformly across every tracked resource type (sub_agent counts
     * as its own type).
     *
     * <p>Soft-fail on any lookup error - a transient read issue must never block
     * execution; we revert to the YAML default.</p>
     *
     * <p>Signature note: this resolver takes {@code credentials} directly instead
     * of a {@code ToolExecutionContext} because sub-agent execution is dispatched
     * off the loop's tool-call payload, which exposes the raw credential map (no
     * synthetic context). Semantically identical to the context-based resolvers in
     * AgentCrudModule / SkillCrudModule / InterfaceCrudModule / DataSourceTableModule.</p>
     */
    int resolveMaxPerResourcePerTurn(Map<String, Object> credentials) {
        int fallback = agentDefaults.getMaxPerResourcePerTurn();
        Integer agentOverride = null;
        if (credentials != null) {
            UUID callerId = getCallerAgentEntityId(credentials);
            if (callerId != null) {
                try {
                    Optional<AgentEntity> entityOpt = agentService.findById(callerId);
                    agentOverride = entityOpt.map(AgentEntity::getMaxPerResourcePerTurn).orElse(null);
                } catch (Exception e) {
                    log.debug("[SUB_AGENT] Failed to resolve per-agent maxPerResourcePerTurn, falling back: {}", e.toString());
                }
            }
        }
        return com.apimarketplace.agent.config.GuardOverrides.resolve(
            agentOverride, credentials,
            com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN,
            fallback);
    }

    /**
     * Execute a sub-agent locally.
     *
     * @param toolCall    the tool call with arguments (agent_id, prompt, context, timeout)
     * @param tenantId    the tenant/user ID
     * @param credentials parent agent's credentials
     * @return the tool result
     */
    public ToolResult execute(ToolCall toolCall, String tenantId, Map<String, Object> credentials) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> params = toolCall.arguments() != null ? toolCall.arguments() : Map.of();

        try {
            return doExecute(toolCall, params, tenantId, credentials, startTime);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[SUB_AGENT] Unexpected error: {}", e.getMessage(), e);
            return buildErrorResult(toolCall, "Sub-agent execution error: " + e.getMessage(), duration);
        }
    }

    @SuppressWarnings("unchecked")
    /** {@code __userRoles__} as the parent carried it, or null when absent or blank. */
    private static String callerRoles(Map<String, Object> credentials) {
        Object roles = credentials != null ? credentials.get(CRED_USER_ROLES) : null;
        return roles instanceof String s && !s.isBlank() ? s : null;
    }

    private ToolResult doExecute(ToolCall toolCall, Map<String, Object> params,
                                  String tenantId, Map<String, Object> credentials, long startTime) {

        // 1. Extract and validate parameters
        String agentIdStr = getStringParam(params, "agent_id");
        if (agentIdStr == null || agentIdStr.isBlank()) {
            return buildFailure(toolCall, startTime,
                "agent_id is required and must be a valid UUID.\n" +
                "Use agent(action='list') to see available agents.");
        }

        UUID agentId;
        try {
            agentId = UUID.fromString(agentIdStr);
        } catch (IllegalArgumentException e) {
            return buildFailure(toolCall, startTime,
                "agent_id is required and must be a valid UUID.\n" +
                "Use agent(action='list') to see available agents.");
        }

        String prompt = getStringParam(params, "prompt");
        if (prompt == null || prompt.isBlank()) {
            return buildFailure(toolCall, startTime,
                "'prompt' is required for agent execution.\n\n" +
                "EXAMPLE:\n" +
                "agent(action='execute', agent_id='<uuid>', prompt='Analyse this data and summarize findings')");
        }

        String userContext = getStringParam(params, "context");
        Integer callerTimeout = getIntParam(params, "timeout");
        // memory defaults to true - sub-agent sees its conversation history
        Boolean memoryParam = params.get("memory") instanceof Boolean b ? b : null;
        boolean memoryEnabled = memoryParam == null || memoryParam; // default true

        // 2. Depth tracking (no hard limit) + caller chain (§4.2 AGENT_BUDGET_HIERARCHY.md)
        // Hoist the callerAgentEntityId read to BEFORE the reservation step - it's needed to
        // build the chain passed to tryReserveChain. Formerly this read happened after context
        // build (~line 307), which was too late for cascade admission.
        int currentDepth = getAgentDepth(credentials);
        UUID callerAgentEntityId = getCallerAgentEntityId(credentials);

        // 3. Rate limit - max executions per turn (Redis-backed for distributed enforcement).
        // Cap comes from caller-agent override → AgentDefaultsConfig.maxPerResourcePerTurn fallback.
        // On Redis failure, we skip rate limiting (graceful degradation) - better to allow
        // execution than to block the agent loop due to a transient Redis issue.
        String turnId = getTurnId(credentials);
        if (turnId != null) {
            int maxExecutions = resolveMaxPerResourcePerTurn(credentials);
            Long count = null;
            try {
                String rateLimitKey = RATE_LIMIT_PREFIX + turnId;
                count = redisTemplate.opsForValue().increment(rateLimitKey);
                if (count != null && count == 1) {
                    // First increment - set TTL so the key auto-expires
                    redisTemplate.expire(rateLimitKey, RATE_LIMIT_TTL);
                }
            } catch (Exception e) {
                log.warn("[SUB_AGENT] Redis rate limit check failed, proceeding: turnId={}, error={}",
                    turnId, e.getMessage());
            }
            if (count != null && count > maxExecutions) {
                return buildFailure(toolCall, startTime,
                    "LIMIT REACHED: You have already executed " + maxExecutions +
                    " sub-agents in this turn.\n\n" +
                    "WHAT TO DO:\n" +
                    "1. Combine your remaining tasks into fewer, more comprehensive prompts\n" +
                    "2. Use the results you already have to answer the user's question\n" +
                    "3. Ask the user if they need additional sub-agent executions");
            }
        }

        var accessDenied = ToolAccessControl.checkWriteAccess(credentials, "agent", "execute");
        if (accessDenied.isPresent()) {
            return buildFailure(toolCall, startTime, accessDenied.get());
        }

        // 4. Access control - check allowedAgentIds
        List<String> allowedAgentIds = getAllowedAgentIds(credentials);
        if (allowedAgentIds != null && !allowedAgentIds.contains(agentId.toString())) {
            log.info("[SUB_AGENT] Agent execute restriction: agent {} not in allowed list", agentId);
            return buildFailure(toolCall, startTime, "This agent is not in your approved agent list.");
        }

        String organizationId = resolveOrganizationId(credentials);
        String organizationRole = resolveOrganizationRole(credentials);

        // 5. Load agent from local DB
        Optional<AgentEntity> entityOpt = organizationId != null
            ? agentService.getAgent(agentId, tenantId, organizationId, organizationRole)
            : agentService.getAgent(agentId, tenantId);
        if (entityOpt.isEmpty()) {
            return buildFailure(toolCall, startTime,
                "Agent not found: " + agentId + "\nUse agent(action='list') to see available agents.");
        }
        AgentEntity entity = entityOpt.get();

        if (entity.getIsActive() != null && !entity.getIsActive()) {
            return buildFailure(toolCall, startTime,
                "Agent '" + entity.getName() + "' is inactive. " +
                "Use agent(action='update', agent_id='" + agentId + "', is_active=true) to reactivate.");
        }

        // Resolve timeout: caller param > entity config > default
        int timeout = clampTimeout(
            callerTimeout != null ? callerTimeout
                : entity.getExecutionTimeout() != null ? entity.getExecutionTimeout()
                : DEFAULT_TIMEOUT_SECONDS);

        // 6. Build full prompt
        String fullPrompt = userContext != null && !userContext.isBlank()
            ? "Context:\n" + userContext + "\n\nTask:\n" + prompt
            : prompt;

        // 7. Build sub-agent credentials
        Map<String, Object> subCredentials = buildSubAgentCredentials(credentials, currentDepth, agentId);
        // The CHILD's own grant, resolved from the CHILD's config with the same
        // resolver the direct path uses. Not inherited from the parent, which
        // would be wrong in both directions: a permissive parent would widen a
        // restricted child, and a restricted one would narrow a child its owner
        // configured deliberately. Written here rather than in the credential
        // builder because that builder only knows the child's id, and an id is
        // not a permission.
        subCredentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY,
                new ArrayList<>(AgentModuleResolver.resolveEnabledModules(entity.getToolsConfig())));
        if (organizationId != null) {
            subCredentials.putIfAbsent("__orgId__", organizationId);
        }
        // Carry the parent's key-route stamp (route + the provider it was resolved for) so
        // buildSubAgentContext can decide what this child inherits once its own execution
        // provider is known (see KeyRoute.inheritFor).
        KeyRoute.stamp(subCredentials, KeyRoute.fromCredentials(credentials),
            credentials != null && credentials.get(KeyRoute.PROVIDER_CREDENTIAL_KEY) instanceof String p ? p : null);

        // Apply toolsConfig resource restrictions to credentials
        applyToolsConfigCredentials(subCredentials, entity.getToolsConfig());

        // Per-agent inactivity watchdog window: carry the entity's setting on the credentials map so
        // both the local loop (AgentLoopService.resolveInactivityWindowMs) and the bridge sub-agent
        // path honor it, without threading a new field through the positional execution DTO.
        if (entity.getInactivityTimeout() != null) {
            subCredentials.put("__inactivityTimeoutSeconds__", entity.getInactivityTimeout());
        }

        // 8. Find or create conversation.
        // Always pass an explicit owner org so the conversation row can never be
        // stamped from a stale ambient thread context (cross-tenant bleed). Prefer
        // the caller-supplied run org; fall back to the sub-agent entity's own org
        // (the authoritative owner of this conversation), never the org-less variant.
        String conversationOrgId = organizationId != null ? organizationId : entity.getOrganizationId();
        String conversationId = conversationServiceClient.findOrCreateAgentConversation(
            agentId.toString(), tenantId, entity.getName(), conversationOrgId);

        // Pass sub-agent's conversationId into credentials for chaining
        if (conversationId != null) {
            subCredentials.put("conversationId", conversationId);
        }

        // 9. Load conversation history if memory is enabled
        List<Message> conversationHistory = List.of();
        if (memoryEnabled && conversationId != null) {
            conversationHistory = loadConversationHistory(conversationId, tenantId, organizationId);
            log.info("[SUB_AGENT] Memory enabled for '{}': loaded {} messages from conversation {}",
                entity.getName(), conversationHistory.size(), conversationId);
        } else {
            log.info("[SUB_AGENT] Memory disabled for '{}': starting with empty history", entity.getName());
        }

        // 10. Save user prompt
        String executionId = UUID.randomUUID().toString();
        if (conversationId != null && fullPrompt != null) {
            saveConversationMessage(conversationId, "user", fullPrompt, null, tenantId, executionId, organizationId);
        }

        // Start streaming
        String streamId = UUID.randomUUID().toString();
        String parentConversationId = getConversationId(credentials);
        String workflowRunId = getWorkflowRunId(credentials);

        // Publish sub_agent_started event to parent
        if (parentConversationId != null) {
            publishSubAgentEvent(parentConversationId, "sub_agent_started",
                entity.getName(), entity.getAvatarUrl(), agentId.toString());
        }

        // ==================== Cascade Budget Reservation (§4.4) ====================
        //
        // Build the chain this child will hold during execution, compute the requested
        // reservation, and call tryReserveChain BEFORE executing. If admission fails we
        // short-circuit with BUDGET_EXHAUSTED / parent_reservation. Otherwise we set
        // reservationHeld = true and the outer try/catch guarantees the reservation is
        // released on any throw before recordObservability transfers settle ownership.

        List<UUID> chainForChild = (callerAgentEntityId != null && budgetReservationService != null)
            ? CredentialsCallerChain.forChild(credentials, callerAgentEntityId)
            : List.of();

        BigDecimal requestedReservation = budgetReservationService != null
            ? resolveRequestedReservation(params, chainForChild)
            : BigDecimal.ZERO;

        boolean reservationHeld = false;
        int subAgentDepth = currentDepth + 1;
        // Hoisted out of the try so the Throwable catch can finalize the stream:
        // a failure escaping after forExecution() would otherwise leak the drain
        // registry entry (heartbeat refreshed forever -> the reconciler skips it).
        ConversationRedisStreamingCallback.ConversationCallback baseCallback = null;

        try {
            // 11a. Admission - reserve on every ancestor in the chain. Throws
            //      InsufficientBudgetException on the first refusal; the caught path below
            //      returns a BUDGET_EXHAUSTED failure WITHOUT releasing anything (no row was
            //      actually updated past the failing ancestor - the surrounding @Transactional
            //      rolled back any earlier UPDATEs).
            if (budgetReservationService != null
                    && requestedReservation != null
                    && requestedReservation.signum() > 0
                    && !chainForChild.isEmpty()) {
                try {
                    budgetReservationService.tryReserveChain(chainForChild, requestedReservation);
                    reservationHeld = true;
                } catch (InsufficientBudgetException ex) {
                    long durationMs = System.currentTimeMillis() - startTime;
                    String error = "Cannot spawn child '" + entity.getName()
                        + "': ancestor agent " + ex.getAgentId()
                        + " has insufficient free budget for reservation " + requestedReservation
                        + " (scope=parent_reservation, BUDGET_EXHAUSTED)";
                    log.warn("[SUB_AGENT] spawn refused by cascade reservation tenant={} ancestor={} reserved={} chain={}",
                        tenantId, ex.getAgentId(), requestedReservation, chainForChild);
                    AgentLoopResult refused = buildBudgetRefusalResult(entity, error, durationMs);
                    String taskId = taskIdFromCredentials(subCredentials);
                    agentActivityPublisher.publishExecutionStarted(
                        agentId.toString(), executionId, entity.getModelName(), "SUB_AGENT", taskId);
                    agentActivityPublisher.publishExecutionCompleted(
                        agentId.toString(), executionId, "FAILED", 0, 0, durationMs, taskId);
                    if (conversationId != null) {
                        saveAssistantResponse(conversationId, tenantId, refused, executionId, organizationId);
                    }
                    if (parentConversationId != null) {
                        publishSubAgentEvent(parentConversationId, "sub_agent_completed",
                            entity.getName(), entity.getAvatarUrl(), agentId.toString());
                    }
                    recordObservability(refused, entity, tenantId, callerAgentEntityId, subAgentDepth,
                        conversationId, parentConversationId, memoryEnabled, List.of(), BigDecimal.ZERO,
                        entity.getSystemPrompt(), fullPrompt, "parent_reservation", /*keyRoute*/ null, subCredentials);
                    return buildFailure(toolCall, startTime, error);
                }
            }

            // 11. Resolve tools from cache
            List<ToolDefinition> tools = resolveTools(entity);
            // CE→cloud web-search relay: hide web_search from sub-agents whose
            // tenant cannot use it (local engine disabled AND not cloud-linked).
            if (webSearchRelayGate != null) {
                tools = webSearchRelayGate.filterExposedTools(tools, tenantId);
            }

            // 12. Build AgentLoopContext
            // BILLED identity: what the ledger, the budget guards, the fleet feed and the
            // bridge relabel all keep using, whatever a link redirects underneath.
            String model = entity.getModelName();
            String provider = entity.getModelProvider();

            // Model execution link: without it a delegated sub-agent runs on the billed
            // provider's own API key even when an admin routed that pair elsewhere, which
            // is the one place a linked model could still hit the key it was linked away
            // from. Only the loop below moves to the execution pair.
            var executionRoute = executionLinkRouter != null
                ? executionLinkRouter.runnableRoute(provider, model, ACTIVITY_SOURCE)
                : null;
            String execProvider = executionRoute != null ? executionRoute.executionProvider() : provider;
            String execModel = executionRoute != null ? executionRoute.executionModel() : model;
            if (executionRoute != null) {
                log.info("[SUB_AGENT] Execution link: billed={}/{} -> exec={}/{}",
                    provider, model, execProvider, execModel);
                if (ExecutionLinkRouter.targetsBridge(executionRoute)) {
                    // Restricted "API mode" on a linked CLI bridge run: platform MCP tools
                    // only, empty cwd, no CLI leak - same contract as a linked agent run.
                    subCredentials.put(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, Boolean.TRUE);
                }
            }

            // Build the guard chain. Tenant guard runs first; the agent guard uses the
            // BudgetResolver to honour weekly/monthly resets and read the real per-agent
            // creditBudget from the loaded entity. A third, INDEPENDENT reservationGuard
            // enforces the per-execution cap = requestedReservation. The chain short-circuits
            // at the tightest guard, so the effective cap is min(tenant, lifetime, reservation).
            BudgetState agentBudget = budgetResolver != null
                ? budgetResolver.resolveAndPersist(entity, Instant.now())
                : BudgetState.disabled();

            ModelCostCalculator calculator = guardChainFactory.resolveCalculator(provider, model);

            TenantBudgetGuard tenantGuard = creditConsumptionClient != null
                ? new TenantBudgetGuard(creditConsumptionClient, calculator)
                : null;

            AgentBudgetGuard lifetimeGuard = agentBudget.isEnabled()
                ? new AgentBudgetGuard(agentBudget.totalBudget(), agentBudget.consumedAfterReset(),
                    agentBudget.creditsReserved(), calculator)
                : null;

            AgentBudgetGuard reservationGuard =
                (requestedReservation != null && requestedReservation.signum() > 0)
                    ? new AgentBudgetGuard(requestedReservation, BigDecimal.ZERO, calculator)
                    : null;

            PreIterationGuard guard = PreIterationGuard.chain(tenantGuard, lifetimeGuard, reservationGuard);

            // ── Build full modular system prompt (same as orchestrator AgentNode) ──
            Set<String> enabledModules = AgentModuleResolver.resolveEnabledModules(entity.getToolsConfig());
            DefaultSystemPrompts.ModularPromptResult promptResult = DefaultSystemPrompts.build(enabledModules, memoryEnabled);
            String fullSystemPrompt = promptResult.systemPrompt();

            // Append custom system prompt from entity (same as orchestrator)
            String customSystemPrompt = entity.getSystemPrompt();
            if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
                fullSystemPrompt = fullSystemPrompt + "\n\n" + customSystemPrompt;
            }

            // Append task delegation summary if agent has pending tasks
            String taskSummary = getTaskSummarySection(entity, tenantId);
            if (taskSummary != null && !taskSummary.isBlank()) {
                fullSystemPrompt = fullSystemPrompt + "\n\n" + taskSummary;
            }

            // Long-term memory, appended last so the stable prefix above stays
            // cacheable. A delegated sub-agent DOES get the workspace's memory:
            // it is doing this workspace's work and needs to know the same facts.
            // What it does not get is another agent's private entries - passing
            // its OWN agentId scopes the query to workspace rows plus its own.
            if (memoryPromptSection != null) {
                try {
                    fullSystemPrompt = memoryPromptSection.appendTo(fullSystemPrompt, organizationId, agentId);
                } catch (RuntimeException memoryUnavailable) {
                    // Degraded, never failed: a delegation that dies because the memory
                    // lookup misbehaved loses the whole sub-agent's work, and the parent
                    // gets an error it cannot act on. Same policy as the other two sites.
                    log.warn("[MEMORY] Prompt enrichment failed for sub-agent {}; running without it: {}",
                        agentId, memoryUnavailable.toString());
                }
            }

            // Inject __taskId__ if this agent has a current in-progress task and
            // the credential doesn't already carry one from the parent.
            if (subCredentials.get("__taskId__") == null && agentTaskService != null) {
                try {
                    agentTaskService.getCurrentInProgressTaskId(tenantId, agentId)
                        .ifPresent(tid -> subCredentials.put("__taskId__", tid.toString()));
                } catch (Exception e) {
                    log.debug("Failed to resolve __taskId__ for agent {}: {}", agentId, e.getMessage());
                }
            }

            // EXECUTION identity (= the billed pair unless a link redirected it). The
            // execution-link bridge-failure fallback below reuses this SAME builder method on
            // the BILLED pair instead of duplicating the builder chain (a second inline
            // AgentLoopContext.builder() would desync ContextWindowWiringTest's 1:1 discovery
            // of "one builder call site = one .contextWindow(...) wiring" per file).
            String userRoles = callerRoles(credentials);
            AgentLoopContext context = buildSubAgentContext(execProvider, execModel, subCredentials,
                fullSystemPrompt, fullPrompt, conversationHistory, tools, entity, timeout, guard,
                userRoles, tenantId, executionId);
            // Whose key the child is billed under: the pin of this context, or of the fallback below.
            KeyRoute executedRoute = context.keyRoute();

            // 13. Create streaming callback, wrapped with fleet activity publishing
            baseCallback =
                conversationRedisStreamingCallback.forExecution(
                    streamId, conversationId, model,
                    parentConversationId, entity.getName(),
                    entity.getAvatarUrl(), agentId.toString(),
                    workflowRunId);

            String agentEntityIdStr = agentId.toString();
            // Scope fleet activity to the specific task this sub-agent is working on (if any).
            // Without this, every card assigned to the agent would shimmer for any execution.
            String taskId = taskIdFromCredentials(subCredentials);
            StreamingCallback callback = wrapWithFleetActivity(baseCallback, agentEntityIdStr, executionId, taskId);

            // The access gate for a bridge this agent NAMES itself sits just below, on the useBridge
            // branch, and only when no execution link chose the route; the execution-link fallback
            // never sees a policy denial because a routed run is not judged at all.
            boolean useBridge = bridgeClient != null && SubAgentBridgeClient.isBridgeProvider(execProvider);

            // Publish fleet activity: execution started
            agentActivityPublisher.publishExecutionStarted(
                agentEntityIdStr, executionId, model, "SUB_AGENT", taskId);

            log.info("[SUB_AGENT] Starting '{}' (id={}) at depth {} with timeout {}s, tools={}, caller={}, reserved={}, chain={}, conversationId={}, memory={}, useBridge={}",
                entity.getName(), agentId, subAgentDepth, timeout, tools.size(), callerAgentEntityId,
                requestedReservation, chainForChild, conversationId, memoryEnabled, useBridge);

            // 14. Execute - route to bridge for CLI providers, otherwise local AgentLoopService
            AgentLoopResult result;
            String bridgeBudgetScope = null;
            if (useBridge) {
                // Refuse a CLI this caller CHOSE and may not use; let a ROUTED one through.
                // Two things were wrong here before. This branch had no access check at ALL, which
                // is how a non-admin's sub-agent COMPLETED twice on claude-code and billed 2617
                // credits to the shared subscription while the same agent's scheduled fires were
                // refused - same agent, same user, opposite outcomes, decided by which path ran.
                // And the gate added to AgentLoopService covers only the else-branch below, so
                // whether the bridge transport happened to be wired decided whether the policy
                // applied at all. The distinction is the one BridgeLoopDispatcher.gateSelection
                // draws: executionRoute == null means the agent names this CLI itself.
                if (bridgeAccessGuard != null && executionRoute == null) {
                    bridgeAccessGuard.enforce(context.tenantId(), context.userRoles(),
                        execProvider, true);
                }
                AgentExecutionResponseDto bridgeResponse = executeBridgeRaw(context, streamId,
                    conversationId, parentConversationId, entity, agentEntityIdStr, workflowRunId, agentBudget);
                bridgeBudgetScope = bridgeResponse != null ? bridgeResponse.budgetScope() : null;

                // A linked sub-agent whose bridge attempt failed before producing anything an end
                // user could see (no content, no tool results) is safe to retry invisibly: like
                // the top-level agent path, the bridge streams to Redis as it goes, so ANY visible
                // output means this branch must not fire. Direct (non-link) bridge sub-agents fall
                // through unchanged below - the billed pair already IS the bridge, nothing distinct
                // to retry on.
                // Same rule as the top-level path: a cancelled run is not retried, or the
                // user pays a full direct-API turn for the sub-agent they just stopped.
                if (executionRoute != null && bridgeResponse != null
                        && !bridgeResponse.success() && bridgeResponse.hasNoVisibleOutput()
                        && !bridgeResponse.wasCancelledByUser()) {
                    if (prometheusMetrics != null) {
                        prometheusMetrics.recordExecutionLinkFallback(provider, model, execProvider);
                    }
                    log.warn("[SUB_AGENT] [EXECUTION_LINK_FALLBACK] bridge dispatch for '{}' failed "
                            + "before producing output (exec={}/{}: {}); retrying on billed pair {}/{}",
                        entity.getName(), execProvider, execModel, bridgeResponse.error(), provider, model);
                    Map<String, Object> fallbackCredentials = new HashMap<>(subCredentials);
                    fallbackCredentials.remove(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY);
                    // Rebuilt via the SAME buildSubAgentContext(...) as the primary attempt, on the
                    // BILLED pair: maxTokens/contextWindow/reasoningEffort all describe "the model
                    // that actually runs the turn" (see linkedSubAgentResolvesModelLimitsOnTheExecution
                    // Pair), which is now the billed pair, not claude-code - reusing the execution
                    // pair's stale values here could send an oversized max_tokens to a smaller billed
                    // model and 400 the very call this fallback exists to rescue.
                    AgentLoopContext directContext = buildSubAgentContext(provider, model, fallbackCredentials,
                        fullSystemPrompt, fullPrompt, conversationHistory, tools, entity, timeout, guard,
                        userRoles, tenantId, executionId);
                    result = agentLoopService.execute(directContext, callback);
                    executedRoute = directContext.keyRoute();
                    // The captured budgetScope described the discarded bridge attempt, not what
                    // actually happened; a stale value here would mislabel the fallback's own
                    // (diagnostic-only, non-financial) recordObservability row.
                    bridgeBudgetScope = null;
                } else {
                    result = convertBridgeResponse(bridgeResponse, provider, model, execProvider);
                }
            } else {
                result = agentLoopService.execute(context, callback);
            }

            // Publish done/error event to conversation stream
            if (result.success()) {
                baseCallback.onComplete(result.response());
            } else if (result.error() != null) {
                baseCallback.onError(result.error());
            } else {
                baseCallback.onComplete(result.response());
            }

            long durationMs = System.currentTimeMillis() - startTime;

            // Publish fleet activity: execution completed
            int totalTokens = result.usage() != null ? result.usage().getTotal() : 0;
            int totalToolCalls = result.toolResults() != null ? result.toolResults().size() : 0;
            agentActivityPublisher.publishExecutionCompleted(
                agentEntityIdStr, executionId,
                result.success() ? "COMPLETED" : "FAILED",
                totalTokens, totalToolCalls, durationMs, taskId);

            log.info("[SUB_AGENT] '{}' completed in {}ms, success={}, iterations={}, tools={}",
                entity.getName(), durationMs, result.success(), result.iterations(),
                result.toolResults() != null ? result.toolResults().size() : 0);

            // 15. Save assistant response to conversation
            if (conversationId != null) {
                saveAssistantResponse(conversationId, tenantId, result, executionId, organizationId);
            }

            // 16. Notify parent
            if (parentConversationId != null) {
                publishSubAgentEvent(parentConversationId, "sub_agent_completed",
                    entity.getName(), entity.getAvatarUrl(), agentId.toString());
            }

            // 17. Transfer settle ownership to recordFromRequest BEFORE the call (§4.5 dual-
            //     refund safety). Once reservationHeld is false, the outer catch will not
            //     refund even if recordObservability/recordFromRequest itself throws - settle
            //     is now the observability service's responsibility.
            reservationHeld = false;
            recordObservability(result, entity, tenantId, callerAgentEntityId, subAgentDepth,
                conversationId, parentConversationId, memoryEnabled, chainForChild, requestedReservation,
                fullSystemPrompt, fullPrompt, bridgeBudgetScope, executedRoute, subCredentials);

            // 18. Build tool result
            return buildToolResult(toolCall, agentId, entity.getName(), result, durationMs, tenantId, credentials);
        } catch (Throwable t) {
            // Finalize the sub-agent stream (publishes the error + finalizes in
            // conversation-service + unregisters from the drain registry). Without
            // this the leaked heartbeat keeps the dead stream ACTIVE forever.
            if (baseCallback != null) {
                try {
                    baseCallback.onError("Sub-agent execution error: " + t.getMessage());
                } catch (Exception cbEx) {
                    log.warn("[SUB_AGENT] Failed to finalize stream after failure: {}", cbEx.getMessage());
                }
            }
            // Outer refund catch (§4.4). Only fires when we threw BEFORE step 17 - meaning
            // recordObservability did not run, so recordFromRequest never got a chance to
            // settle. When reservationHeld is still true we must release the chain here;
            // otherwise the reservation would leak until startup cleanup.
            if (reservationHeld && budgetReservationService != null) {
                try {
                    budgetReservationService.settleReservationChain(
                        chainForChild, requestedReservation, BigDecimal.ZERO);
                    log.warn("[SUB_AGENT] early failure - refunded cascade reservation chain={} amount={}",
                        chainForChild, requestedReservation);
                } catch (Exception settleEx) {
                    log.error("[SUB_AGENT] Failed to refund cascade reservation on early failure chain={} amount={}",
                        chainForChild, requestedReservation, settleEx);
                }
            }
            throw t;
        }
    }

    // ==================== Fleet Activity Wrapper ====================

    /**
     * Wraps a StreamingCallback to additionally publish fleet activity events
     * (tool_call_started / tool_call_completed) to the agent:activity channel.
     * Same pattern as AgentRemoteExecutionService.wrapWithActivityPublishing().
     */
    private StreamingCallback wrapWithFleetActivity(StreamingCallback delegate,
                                                     String agentEntityId, String executionId, String taskId) {
        return new StreamingCallback() {
            @Override public void onChunk(String content) {
                if (delegate != null) delegate.onChunk(content);
            }
            @Override public void onThinking(String thinking) {
                if (delegate != null) delegate.onThinking(thinking);
            }
            @Override public void onToolCall(ToolCall toolCall) {
                agentActivityPublisher.publishToolCallStarted(
                    agentEntityId, executionId, toolCall.toolName(), toolCall.id(), taskId);
                if (delegate != null) delegate.onToolCall(toolCall);
            }
            @Override public void onToolResult(ToolResult result) {
                String toolName = result.toolCall() != null ? result.toolCall().toolName() : null;
                String toolCallId = result.toolCall() != null ? result.toolCall().id() : null;
                agentActivityPublisher.publishToolCallCompleted(
                    agentEntityId, executionId, toolName, toolCallId,
                    result.success(), result.durationMs(), taskId);
                if (delegate != null) delegate.onToolResult(result);
            }
            @Override public void onComplete(CompletionResponse response) {
                if (delegate != null) delegate.onComplete(response);
            }
            @Override public void onError(String error) {
                if (delegate != null) delegate.onError(error);
            }
            @Override public boolean shouldStop() {
                return delegate != null && delegate.shouldStop();
            }
        };
    }

    // ==================== Bridge Execution ====================

    /**
     * Execute a sub-agent via the bridge server (for CLI-based providers).
     * Same contract as the local AgentLoopService path: returns AgentLoopResult with
     * the same fields so downstream code (observability, persistence, tool result) is unchanged.
     */
    /**
     * Execute via bridge and return the raw DTO (so caller can extract budgetScope etc.).
     * Returns a synthetic failure DTO when the bridge returns null.
     */
    private AgentExecutionResponseDto executeBridgeRaw(AgentLoopContext context, String streamId,
                                              String conversationId, String parentConversationId,
                                              AgentEntity entity, String agentIdStr,
                                              String workflowRunId, BudgetState agentBudget) {

        long bridgeStart = System.currentTimeMillis();
        AgentExecutionRequestDto dto = buildBridgeRequest(context, streamId, conversationId,
            parentConversationId, entity, agentIdStr, workflowRunId, agentBudget);

        AgentExecutionResponseDto response = bridgeClient.execute(dto);

        if (response == null) {
            log.error("[SUB_AGENT_BRIDGE] Bridge returned null response for sub-agent '{}' (id={})",
                entity.getName(), agentIdStr);
            long durationMs = System.currentTimeMillis() - bridgeStart;
            return new AgentExecutionResponseDto(false, null, null, null, 0, null,
                "Bridge execution failed: no response from bridge server",
                durationMs, context.provider(), context.model(), null, "ERROR", null, null, null, null, null, null, null);
        }

        log.info("[SUB_AGENT_BRIDGE] Bridge completed for '{}': success={}, iterations={}, duration={}ms",
            entity.getName(), response.success(), response.iterations(), response.durationMs());

        return response;
    }

    /**
     * Build AgentExecutionRequestDto for bridge dispatch.
     * Mirrors ConversationAgentService.buildExecutionRequest() but for the sub-agent context.
     */
    private AgentExecutionRequestDto buildBridgeRequest(AgentLoopContext context, String streamId,
                                                         String conversationId, String parentConversationId,
                                                         AgentEntity entity, String agentIdStr,
                                                         String workflowRunId, BudgetState agentBudget) {
        // Convert tools to List<Map>
        List<Map<String, Object>> toolMaps = null;
        if (context.tools() != null) {
            toolMaps = context.tools().stream()
                .map(this::toolDefToMap)
                .toList();
        }

        // Convert conversation history to List<Map>
        List<Map<String, Object>> historyMaps = null;
        if (context.conversationHistory() != null) {
            historyMaps = context.conversationHistory().stream()
                .map(msg -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("role", msg.role() != null ? msg.role().name() : "USER");
                    map.put("content", msg.content());
                    if (msg.toolCallId() != null) map.put("toolCallId", msg.toolCallId());
                    if (msg.toolName() != null) map.put("toolName", msg.toolName());
                    return map;
                })
                .toList();
        }

        // Resolve tenant balance for bridge budget enforcement
        Double tenantBalance = null;
        if (creditConsumptionClient != null && context.tenantId() != null) {
            try {
                BigDecimal balance = creditConsumptionClient.fetchLlmSpendableBalance(context.tenantId());
                tenantBalance = balance != null ? balance.doubleValue() : null;
            } catch (Exception e) {
                log.warn("[SUB_AGENT_BRIDGE] Failed to fetch tenant balance: {}", e.getMessage());
            }
        }

        return new AgentExecutionRequestDto(
            context.userPrompt(),
            context.systemPrompt(),
            context.provider(),
            context.model(),
            context.temperature(),
            context.maxTokens(),
            toolMaps,
            context.autoDiscoverTools(),
            context.maxTools(),
            context.maxIterations(),
            context.executionTimeout(),
            historyMaps,
            context.tenantId(),
            null,   // runId
            null,   // nodeId
            context.variables(),
            context.credentials(),
            agentBudget.isEnabled() ? agentBudget.totalBudget().doubleValue() : null,   // maxCreditBudget
            streamId,
            null,   // itemIndex
            null,   // loopIteration
            conversationId,
            "conversation",
            parentConversationId,
            entity.getName(),
            entity.getAvatarUrl(),
            agentIdStr,
            workflowRunId,
            null,   // attachments
            agentIdStr,   // agentEntityId for fleet activity
            tenantBalance,
            null,   // pricingRates
            agentBudget.isEnabled() ? agentBudget.consumedAfterReset().doubleValue() : null,   // creditsConsumedSoFar
            entity.getLoopIdenticalStop(),
            entity.getLoopConsecutiveStop(),
            UUID.randomUUID().toString(),  // executionId - fresh id for the spawned sub-agent execution
            "SUB_AGENT",
            context.reasoningEffort(),  // resolved on the context (agent setting → model default)
            // enabledModules - the DIRECT sub-agent path is scoped via the explicit toolMaps
            // (resolveTools), but the BRIDGE sub-agent path ignores toolMaps and builds its own
            // MCP tool set, so it needs the canonical module keys. Resolved from the child
            // entity's toolsConfig with the same AgentModuleResolver the direct path uses, and
            // ALWAYS sent: a null here made the bridge keep every module, so a child with no
            // toolsConfig got the credit-spending generation tool while the
            // direct path (correctly) withheld them. resolveEnabledModules(null) is
            // AgentModuleResolver.NO_CONFIG_MODULES, which excludes exactly those two.
            List.copyOf(AgentModuleResolver.resolveEnabledModules(entity.getToolsConfig()))
        );
    }

    /**
     * Convert bridge response DTO back to AgentLoopResult.
     * Preserves all fields so downstream observability and persistence work unchanged.
     */
    /**
     * @param provider          the BILLED provider - what the observability row and the
     *                          ledger are stamped with (the sub-agent entity's own pair)
     * @param executionProvider the provider that actually RAN this turn, which under a
     *                          model execution link is a CLI bridge. Its token counts are
     *                          re-expressed in the billed provider's convention below:
     *                          the Claude Code bridge folds the cache into its prompt
     *                          total, so handing it straight to billing under an
     *                          Anthropic label charges the cache twice.
     */
    // Package-private so the convention conversion can be pinned directly: driving it
    // through execute() would need the whole bridge + guard + observability stack, and
    // the thing worth testing is one arithmetic step.
    AgentLoopResult convertBridgeResponse(AgentExecutionResponseDto response,
                                                    String provider, String model,
                                                    String executionProvider) {
        if (!response.success()) {
            AgentStopReason stopReason = parseStopReason(response.stopReason());
            // A failed run is not a free run: carry back what the CLI spent before it
            // stopped, rather than reporting zero for a turn that was paid for.
            //
            // Through the SAME convention conversion as the success path below, and for
            // the same reason the javadoc above gives: the counts come from the CLI that
            // ran, the ledger is stamped with the pair that is billed, and handing one
            // convention to the other charges the cache twice. Carrying the raw numbers
            // here would have turned an under-bill into a roughly fourfold OVER-bill on
            // a claude-code-executed sub-agent - the more expensive mistake of the two.
            return AgentLoopResult.builder()
                .success(false)
                .error(response.error())
                .toolResults(Collections.emptyList())
                .iterations(response.iterations())
                .durationMs(response.durationMs())
                .provider(response.provider() != null ? response.provider() : provider)
                .model(response.model() != null ? response.model() : model)
                .stopReason(stopReason)
                .conversationHistory(convertBridgeConversationHistory(response.conversationHistory()))
                .usage(response.totalUsage() != null
                    ? TokenUsageConventions.toBilledConvention(
                        convertUsageInfo(response.totalUsage()), executionProvider, provider)
                    : null)
                .usagePerIteration(response.usagePerIteration() != null
                    ? response.usagePerIteration().stream()
                        .map(this::convertUsageInfo)
                        .map(u -> TokenUsageConventions.toBilledConvention(u, executionProvider, provider))
                        .toList()
                    : Collections.emptyList())
                .iterationDurations(Collections.emptyList())
                .finishReasonsPerIteration(Collections.emptyList())
                .build();
        }

        String content = response.content() != null ? response.content()
            : response.finalResponse();

        CompletionResponse completionResponse = CompletionResponse.builder()
            .content(content)
            .finishReason("stop")
            .build();

        UsageInfo usage = response.totalUsage() != null
            ? TokenUsageConventions.toBilledConvention(
                convertUsageInfo(response.totalUsage()), executionProvider, provider)
            : null;

        List<UsageInfo> usagePerIteration = response.usagePerIteration() != null
            ? response.usagePerIteration().stream()
                .map(this::convertUsageInfo)
                .map(u -> TokenUsageConventions.toBilledConvention(u, executionProvider, provider))
                .toList()
            : Collections.emptyList();

        AgentStopReason stopReason = parseStopReason(response.stopReason());

        // Convert bridge conversation history, or synthesize from content so
        // observability always has the ASSISTANT response visible in metrics.
        List<Message> bridgeHistory = convertBridgeConversationHistory(response.conversationHistory());
        if (bridgeHistory.isEmpty() && content != null && !content.isBlank()) {
            bridgeHistory = List.of(new Message(Message.Role.ASSISTANT, content, null, null, null, null));
        }

        return AgentLoopResult.builder()
            .success(true)
            .response(completionResponse)
            .content(content)
            .toolResults(Collections.emptyList()) // bridge tool results are internal
            .iterations(response.iterations())
            .usage(usage)
            .durationMs(response.durationMs())
            .provider(response.provider() != null ? response.provider() : provider)
            .model(response.model() != null ? response.model() : model)
            .conversationHistory(bridgeHistory)
            .stopReason(stopReason)
            .metrics(response.metrics() != null ? response.metrics() : Map.of())
            .usagePerIteration(usagePerIteration)
            .iterationDurations(response.iterationDurations() != null
                ? response.iterationDurations() : Collections.emptyList())
            .finishReasonsPerIteration(response.finishReasonsPerIteration() != null
                ? response.finishReasonsPerIteration() : Collections.emptyList())
            .build();
    }

    private UsageInfo convertUsageInfo(Map<String, Object> usageMap) {
        return new UsageInfo(
            getIntFromMap(usageMap, "promptTokens"),
            getIntFromMap(usageMap, "completionTokens"),
            getIntFromMap(usageMap, "totalTokens"),
            getIntFromMap(usageMap, "cacheCreationInputTokens"),
            getIntFromMap(usageMap, "cacheReadInputTokens"),
            getIntFromMap(usageMap, "cachedTokens"),
            getIntFromMap(usageMap, "reasoningTokens"),
            getIntFromMap(usageMap, "thoughtsTokenCount"),
            getIntFromMap(usageMap, "cachedContentTokenCount")
        );
    }

    private Integer getIntFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    /**
     * Convert bridge conversation history (List of Maps) to Message objects.
     * Returns empty list if input is null or empty.
     */
    private List<Message> convertBridgeConversationHistory(List<Map<String, Object>> bridgeHistory) {
        if (bridgeHistory == null || bridgeHistory.isEmpty()) {
            return Collections.emptyList();
        }
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> msgMap : bridgeHistory) {
            String roleStr = msgMap.get("role") instanceof String s ? s : "ASSISTANT";
            Message.Role role;
            try {
                role = Message.Role.valueOf(roleStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                role = Message.Role.ASSISTANT;
            }
            String content = msgMap.get("content") instanceof String s ? s : null;
            String toolCallId = msgMap.get("toolCallId") instanceof String s ? s : null;
            String toolName = msgMap.get("toolName") instanceof String s ? s : null;
            messages.add(new Message(role, content, toolCallId, toolName, null, null));
        }
        return messages;
    }

    private AgentStopReason parseStopReason(String stopReasonStr) {
        if (stopReasonStr == null) return AgentStopReason.COMPLETED;
        try {
            return AgentStopReason.valueOf(stopReasonStr);
        } catch (IllegalArgumentException e) {
            return AgentStopReason.COMPLETED;
        }
    }

    private Map<String, Object> toolDefToMap(ToolDefinition tool) {
        Map<String, Object> map = new HashMap<>();
        if (tool.id() != null) map.put("id", tool.id());
        if (tool.name() != null) map.put("name", tool.name());
        if (tool.description() != null) map.put("description", tool.description());
        if (tool.apiSlug() != null) map.put("apiSlug", tool.apiSlug());
        if (tool.toolSlug() != null) map.put("toolSlug", tool.toolSlug());
        if (tool.parameters() != null) map.put("parameters", tool.parameters());
        if (tool.requiredParameters() != null) map.put("requiredParameters", tool.requiredParameters());
        if (tool.metadata() != null) map.put("metadata", tool.metadata());
        if (tool.timeoutMs() != null) map.put("timeoutMs", tool.timeoutMs());
        return map;
    }

    /**
     * Resolve the per-execution cascade reservation (§4.4 AGENT_BUDGET_HIERARCHY.md).
     *
     * <ol>
     *   <li>An explicit {@code budget_reservation} tool parameter wins (non-negative).</li>
     *   <li>Empty chain (root invocation) → no reservation, return {@link BigDecimal#ZERO}.</li>
     *   <li>Otherwise default = min free across every ancestor in the chain. A null result
     *       means every ancestor is unlimited → return {@link BigDecimal#ZERO} (no binding cap
     *       from the chain, the child runs against its own lifetime cap).</li>
     * </ol>
     */
    BigDecimal resolveRequestedReservation(Map<String, Object> parameters, List<UUID> chain) {
        if (parameters != null) {
            Object explicit = parameters.get("budget_reservation");
            if (explicit != null) {
                BigDecimal value = toBigDecimal(explicit);
                if (value == null || value.signum() < 0) {
                    throw new IllegalArgumentException(
                        "budget_reservation must be a non-negative number");
                }
                return value;
            }
        }
        if (chain == null || chain.isEmpty() || budgetReservationService == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal minFree = budgetReservationService.getMinFreeAcrossChain(chain);
        return minFree != null ? minFree : BigDecimal.ZERO;
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (raw instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s.trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    // ==================== Conversation Memory ====================

    /**
     * Load recent conversation history and convert to Message objects.
     * Only loads user and assistant messages (skips tool messages for brevity).
     */
    private List<Message> loadConversationHistory(String conversationId, String tenantId, String organizationId) {
        try {
            List<Map<String, Object>> rawMessages = organizationId != null
                ? conversationServiceClient.getConversationMessages(
                    conversationId, DEFAULT_MEMORY_LIMIT, tenantId, organizationId)
                : conversationServiceClient.getConversationMessages(
                    conversationId, DEFAULT_MEMORY_LIMIT, tenantId);
            if (rawMessages == null || rawMessages.isEmpty()) return List.of();

            List<Message> history = new ArrayList<>();
            for (Map<String, Object> msg : rawMessages) {
                String role = msg.get("role") != null ? msg.get("role").toString().toLowerCase() : null;
                String content = msg.get("content") != null ? msg.get("content").toString() : null;
                if (role == null || content == null || content.isBlank()) continue;

                switch (role) {
                    case "user" -> history.add(Message.user(content));
                    case "assistant" -> history.add(Message.assistant(content));
                    // Skip system/tool messages - they are context-specific to each execution
                }
            }
            return history;
        } catch (Exception e) {
            log.warn("[SUB_AGENT] Failed to load conversation history from {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    // ==================== Tool Resolution ====================

    /**
     * Resolve the core tools a sub-agent may use, scoped by its entity's {@code toolsConfig}.
     *
     * <p>Canonical resolution - IDENTICAL to chat ({@code AgentContextBuilder}) and the
     * workflow agent node ({@code AgentNode} → {@code AgentRemoteExecutionService}): the
     * enabled MODULE set is derived via {@link AgentModuleResolver#resolveEnabledModules} and
     * turned into core tool names via the same {@link DefaultSystemPrompts#build}. This
     * replaced an ad-hoc path that diverged from the other two ({@code mode=none} → zero
     * tools; {@code mode=custom} → filter by the raw {@code tools} list rather than the
     * family grants), so the SAME agent now exposes the SAME core tool set whether it runs
     * as a sub-agent, in chat, or in a workflow.
     *
     * <p>{@code toolsConfig == null} is handled by the resolver itself
     * ({@link AgentModuleResolver#NO_CONFIG_MODULES}) - there is deliberately NO null
     * short-circuit here. The one this replaced returned the UNFILTERED cache, so a child
     * agent row with no toolsConfig received the credit-spending {@code generation} and
     * {@code generation} tool and could spend the customer's balance with no grant
     * set anywhere. "No config" is not "everything".
     */
    private List<ToolDefinition> resolveTools(AgentEntity entity) {
        Map<String, Object> toolsConfig = entity.getToolsConfig();
        Set<String> enabledModules = AgentModuleResolver.resolveEnabledModules(toolsConfig);
        Set<String> coreToolNames = DefaultSystemPrompts.build(enabledModules, false).coreToolNames();
        return coreToolsCache.getCoreTools(coreToolNames);
    }

    // ==================== Result Builder ====================

    private ToolResult buildToolResult(ToolCall toolCall, UUID agentId, String agentName,
                                        AgentLoopResult result, long durationMs,
                                        String tenantId, Map<String, Object> credentials) {
        String fullResponse = result.success() ? result.content() : result.error();
        boolean wasTruncated = fullResponse != null && fullResponse.length() > MAX_RESPONSE_LENGTH;
        String response = truncateResponse(fullResponse);

        // Save full content if truncated
        String fullContentToolCallId = null;
        if (wasTruncated) {
            fullContentToolCallId = saveFullSubAgentResult(
                agentId, agentName, fullResponse, tenantId, credentials, durationMs);
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("agent_id", agentId.toString());
        resultMap.put("agent_name", agentName);
        resultMap.put("status", result.success() ? "COMPLETED" : "FAILED");
        resultMap.put("response", response);
        resultMap.put("iterations", result.iterations());
        resultMap.put("tools_used", extractToolNames(result));
        resultMap.put("duration_ms", durationMs);
        resultMap.put("stop_reason", result.stopReason() != null
            ? result.stopReason().name() : "UNKNOWN");

        if (fullContentToolCallId != null) {
            resultMap.put("full_response_tool_call_id", fullContentToolCallId);
            resultMap.put("_hint", "Response was truncated. Use get_tool_result(tool_call_id=\""
                + fullContentToolCallId + "\") to retrieve the full content.");
        }

        String content;
        try {
            content = objectMapper.writeValueAsString(resultMap);
        } catch (Exception e) {
            content = resultMap.toString();
        }

        return ToolResult.builder()
            .toolCall(toolCall)
            .success(true) // wrapper always succeeds, inner status in content
            .content(content)
            .durationMs(durationMs)
            .build();
    }

    private String saveFullSubAgentResult(UUID agentId, String agentName, String fullContent,
                                           String tenantId, Map<String, Object> credentials,
                                           long durationMs) {
        String parentConversationId = getConversationId(credentials);
        if (parentConversationId == null) {
            return null;
        }

        String toolCallId = "subagent_" + agentId.toString().substring(0, 8) + "_" + System.currentTimeMillis();

        try {
            String organizationId = resolveOrganizationId(credentials);
            String resultId = organizationId != null
                ? conversationServiceClient.saveToolResult(
                    parentConversationId, tenantId,
                    "agent_execute:" + agentName, toolCallId,
                    true, durationMs, fullContent, null, null, organizationId)
                : conversationServiceClient.saveToolResult(
                    parentConversationId, tenantId,
                    "agent_execute:" + agentName, toolCallId,
                    true, durationMs, fullContent, null);

            if (resultId != null) {
                log.info("[SUB_AGENT] Saved full response ({} chars) as toolCallId={}", fullContent.length(), toolCallId);
                return toolCallId;
            }
        } catch (Exception e) {
            log.warn("[SUB_AGENT] Failed to save full result for '{}': {}", agentName, e.getMessage());
        }
        return null;
    }

    // ==================== Conversation Persistence ====================

    private void saveAssistantResponse(String conversationId, String tenantId,
                                        AgentLoopResult result, String executionId,
                                        String organizationId) {
        try {
            String content = result.content();
            if ((content == null || content.isBlank()) && !result.success()) {
                content = result.error();
            }
            String toolCallsJson = buildToolCallsJson(result);

            if ((content != null && !content.isBlank()) || toolCallsJson != null) {
                saveConversationMessage(
                    conversationId, "assistant", content, toolCallsJson, tenantId, executionId, organizationId);
            }
        } catch (Exception e) {
            log.warn("[SUB_AGENT] Failed to save assistant response to conversation {}: {}",
                conversationId, e.getMessage());
        }
    }

    private String taskIdFromCredentials(Map<String, Object> credentials) {
        if (credentials == null) {
            return null;
        }
        Object taskIdRaw = credentials.get("__taskId__");
        return taskIdRaw != null ? taskIdRaw.toString() : null;
    }

    private void saveConversationMessage(String conversationId, String role, String content,
                                         String toolCallsJson, String tenantId,
                                         String executionId, String organizationId) {
        if (organizationId != null) {
            conversationServiceClient.saveMessage(
                conversationId, role, content, toolCallsJson, tenantId, executionId, organizationId);
            return;
        }
        conversationServiceClient.saveMessage(conversationId, role, content, toolCallsJson, tenantId, executionId);
    }

    private String buildToolCallsJson(AgentLoopResult result) {
        List<ToolResult> toolResults = result.toolResults();
        if (toolResults == null || toolResults.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (ToolResult tr : toolResults) {
            Map<String, Object> entry = new LinkedHashMap<>();
            ToolCall tc = tr.toolCall();
            String toolCallId = tc != null ? tc.id() : UUID.randomUUID().toString();
            String toolName = tc != null ? tc.toolName() : "unknown";

            entry.put("id", toolCallId);
            entry.put("toolName", toolName);
            if (tc != null && tc.arguments() != null) {
                try {
                    entry.put("arguments", objectMapper.writeValueAsString(tc.arguments()));
                } catch (Exception e) {
                    entry.put("arguments", tc.arguments().toString());
                }
            }
            entry.put("success", tr.success());
            entry.put("timestamp", System.currentTimeMillis());
            if (tr.durationMs() != null) entry.put("durationMs", tr.durationMs());
            if (tr.error() != null) entry.put("error", tr.error());

            // Metadata: visualization, iconSlug, displayToolName
            if (tr.metadata() != null) {
                Map<String, Object> meta = tr.metadata();
                if (meta.get("visualization") instanceof Map<?,?> vizMap && ((Map<?,?>) vizMap).containsKey("id")) {
                    entry.put("visualization", vizMap);
                }
                if (meta.get("iconSlug") != null) entry.put("iconSlug", meta.get("iconSlug"));
                if (meta.get("toolName") != null) entry.put("displayToolName", meta.get("toolName"));
                if (meta.get("diff") != null) entry.put("diff", meta.get("diff"));
                if (meta.get("gitStatus") != null) entry.put("gitStatus", meta.get("gitStatus"));
            }

            entries.add(entry);
        }

        try {
            return objectMapper.writeValueAsString(entries);
        } catch (Exception e) {
            log.warn("[SUB_AGENT] Failed to serialize toolCalls JSON: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Observability ====================

    private AgentLoopResult buildBudgetRefusalResult(AgentEntity entity, String error, long durationMs) {
        return AgentLoopResult.builder()
            .success(false)
            .content(error)
            .error(error)
            .toolResults(Collections.emptyList())
            .iterations(0)
            .durationMs(durationMs)
            .provider(entity.getModelProvider())
            .model(entity.getModelName())
            .conversationHistory(List.of(Message.assistant(error)))
            .stopReason(AgentStopReason.BUDGET_EXHAUSTED)
            .metrics(Map.of("budgetScope", "parent_reservation"))
            .usagePerIteration(Collections.emptyList())
            .iterationDurations(Collections.emptyList())
            .finishReasonsPerIteration(Collections.emptyList())
            .build();
    }

    private void recordObservability(AgentLoopResult result, AgentEntity entity,
                                      String tenantId, UUID callerAgentEntityId,
                                      int depth, String conversationId, String parentConversationId,
                                      boolean memoryEnabled,
                                      List<UUID> callerChain, BigDecimal reservedAmount,
                                      String systemPrompt, String userPrompt,
                                      String budgetScope, KeyRoute keyRoute,
                                      Map<String, Object> credentials) {
        try {
            AgentObservabilityRequest request = new AgentObservabilityRequest();
            request.setTenantId(tenantId);
            // PR20 - workspace identity from the inbound HTTP request that
            // triggered this sub-agent dispatch (PR16 forwarder set the header
            // on the caller). NULL = personal scope.
            request.setOrganizationId(resolveOrganizationId(credentials));
            request.setAgentEntityId(entity.getId());
            request.setAgentType("SUB_AGENT");
            // Explicitly set source so AgentObservabilityService doesn't fall through
            // to "WORKFLOW" when callerAgentEntityId is null (e.g., parent's __agentId__
            // not in credentials on certain bridge paths).
            request.setSource("SUB_AGENT");
            request.setProvider(entity.getModelProvider());
            request.setModel(entity.getModelName());
            // Whose key this child ran on: the pin of the context that produced the result. A
            // bridge child holds no key (PLATFORM); its execution-link fallback re-pins for the
            // billed pair. Never read off the credentials map: the parent stamp lives there.
            request.setKeyRoute(keyRoute != null ? keyRoute.name() : null);
            request.setStatus(result.success() ? "COMPLETED" : "FAILED");
            request.setStopReason(result.stopReason() != null ? result.stopReason().name() : null);
            request.setBudgetScope(budgetScope);
            request.setErrorMessage(result.error());
            request.setDurationMs(result.durationMs());
            request.setIterationCount(result.iterations());
            request.setCallerAgentId(callerAgentEntityId);
            request.setNestingDepth(depth);
            request.setMemoryEnabled(memoryEnabled);
            request.setConversationId(conversationId);
            // Parent conversation that spawned this sub-agent - distinct from the
            // sub-agent's own conversationId above. Lets a conversation-scoped
            // observability view surface this spawned execution under the parent.
            request.setParentConversationId(parentConversationId);
            // Cascade budget reservation (§4.5 AGENT_BUDGET_HIERARCHY.md). When non-null,
            // AgentObservabilityService.recordFromRequest calls settleReservationChain
            // atomically with the credit consumption write - taking over settle ownership
            // from the SubAgentExecutionHandler outer catch.
            if (callerChain != null && !callerChain.isEmpty()) {
                request.setCallerChain(callerChain);
                request.setReservedAmount(reservedAmount != null ? reservedAmount : BigDecimal.ZERO);
            }
            // Persist the system prompt actually sent to the LLM (augmented with task
            // delegation summary), not the raw entity.systemPrompt. Falls back to the
            // entity value if augmentation was skipped upstream. Blank values are
            // normalized to null to avoid persisting empty-string rows when neither
            // the entity nor the task summary contributed any content.
            String effectiveSystemPrompt = systemPrompt != null ? systemPrompt : entity.getSystemPrompt();
            if (effectiveSystemPrompt != null && effectiveSystemPrompt.isBlank()) {
                effectiveSystemPrompt = null;
            }
            if (effectiveSystemPrompt != null) {
                request.setSystemPrompt(effectiveSystemPrompt);
            }

            // Task linkage - extract __taskId__ from credentials for execution→task tracing.
            // The credential may have been populated before execution (line ~384) when
            // the agent already had an in_progress task, OR it may still be empty if the
            // agent only picked up its task during execution (via inbox). In the latter
            // case, re-resolve now so the execution gets linked retroactively.
            if (credentials != null) {
                Object rawTaskId = credentials.get("__taskId__");
                if (rawTaskId == null && agentTaskService != null) {
                    try {
                        rawTaskId = agentTaskService.resolveTaskIdForExecution(tenantId, entity.getId())
                                .map(UUID::toString).orElse(null);
                    } catch (Exception ignored) { /* best-effort */ }
                }
                if (rawTaskId != null) {
                    try {
                        request.setTaskId(UUID.fromString(rawTaskId.toString()));
                    } catch (IllegalArgumentException ignored) {
                        // invalid UUID format - skip
                    }
                }
            }

            // LLM config
            if (entity.getTemperature() != null) request.setTemperature(entity.getTemperature().doubleValue());
            request.setMaxTokensConfig(entity.getMaxTokens());
            request.setMaxIterationsConfig(entity.getMaxIterations());

            // Token usage
            if (result.usage() != null) {
                var usage = result.usage();
                request.setPromptTokens(usage.promptTokens() != null ? usage.promptTokens() : 0);
                request.setCompletionTokens(usage.completionTokens() != null ? usage.completionTokens() : 0);
                request.setTotalTokens(usage.getTotal());
                if (usage.cacheCreationInputTokens() != null) request.setCacheCreationTokens(usage.cacheCreationInputTokens());
                if (usage.cacheReadInputTokens() != null) request.setCacheReadTokens(usage.cacheReadInputTokens());
                if (usage.cachedTokens() != null) request.setCachedTokens(usage.cachedTokens());
                if (usage.reasoningTokens() != null) request.setReasoningTokens(usage.reasoningTokens());
            }

            // Tool count
            request.setTotalToolCalls(result.toolResults() != null ? result.toolResults().size() : 0);

            // Loop detection
            if (result.metrics() != null) {
                Object loopDetected = result.metrics().get("loopDetected");
                request.setLoopDetected(Boolean.TRUE.equals(loopDetected));
                Object loopType = result.metrics().get("loopType");
                if (loopType != null) request.setLoopType(loopType.toString());
                Object loopToolName = result.metrics().get("loopToolName");
                if (loopToolName != null) request.setLoopToolName(loopToolName.toString());
            }

            // Unique tool count
            if (result.toolResults() != null && !result.toolResults().isEmpty()) {
                Set<String> uniqueTools = new HashSet<>();
                for (var tr : result.toolResults()) {
                    if (tr.toolCall() != null && tr.toolCall().toolName() != null) {
                        uniqueTools.add(tr.toolCall().toolName());
                    }
                }
                request.setUniqueToolCount(uniqueTools.size());
            }

            // Conversation history → messages
            // AgentLoopResult.conversationHistory() comes from LoopExecutionState
            // .getCurrentExecutionMessages(), which excludes the system prompt and the
            // user prompt. Prepend them here so the metrics conversation tab shows the
            // full turn (parent's prompt + sub-agent's reply), matching the chat path.
            {
                var messages = new ArrayList<AgentObservabilityRequest.MessageData>();
                int seq = 0;
                int iterationCounter = 0;
                if (effectiveSystemPrompt != null && !effectiveSystemPrompt.isBlank()) {
                    var sys = new AgentObservabilityRequest.MessageData();
                    sys.setSequenceNumber(seq++);
                    sys.setRole("SYSTEM");
                    sys.setContent(effectiveSystemPrompt);
                    messages.add(sys);
                }
                if (userPrompt != null && !userPrompt.isBlank()) {
                    var usr = new AgentObservabilityRequest.MessageData();
                    usr.setSequenceNumber(seq++);
                    usr.setRole("USER");
                    usr.setContent(userPrompt);
                    usr.setIterationNumber(iterationCounter);
                    messages.add(usr);
                }
                if (result.conversationHistory() != null && !result.conversationHistory().isEmpty()) {
                    for (var msg : result.conversationHistory()) {
                        if (msg.role() == Message.Role.ASSISTANT) iterationCounter++;
                        var md = new AgentObservabilityRequest.MessageData();
                        md.setSequenceNumber(seq++);
                        md.setRole(msg.role() != null ? msg.role().name() : "UNKNOWN");
                        md.setContent(msg.content());
                        md.setToolCallId(msg.toolCallId());
                        md.setToolName(msg.toolName());
                        if (msg.role() != null && msg.role() != Message.Role.SYSTEM) {
                            md.setIterationNumber(iterationCounter);
                        }
                        messages.add(md);
                    }
                }
                if (!messages.isEmpty()) {
                    request.setMessages(messages);
                }
            }

            // Tool results → tool calls
            if (result.toolResults() != null && !result.toolResults().isEmpty()) {
                var toolCalls = new ArrayList<AgentObservabilityRequest.ToolCallData>();
                int seq = 0;
                for (var tr : result.toolResults()) {
                    var tc = new AgentObservabilityRequest.ToolCallData();
                    tc.setSequenceNumber(seq++);
                    if (tr.toolCall() != null) {
                        tc.setToolCallId(tr.toolCall().id());
                        tc.setToolName(tr.toolCall().toolName());
                        tc.setArguments(tr.toolCall().arguments());
                        if (tr.toolCall().index() != null) tc.setParallelIndex(tr.toolCall().index());
                    }
                    tc.setSuccess(tr.success());
                    tc.setResult(tr.content());
                    tc.setDurationMs(tr.durationMs() != null ? tr.durationMs() : 0L);
                    toolCalls.add(tc);
                }
                request.setToolCalls(toolCalls);
            }

            // Per-iteration data
            if (result.usagePerIteration() != null && !result.usagePerIteration().isEmpty()) {
                var iterations = new ArrayList<AgentObservabilityRequest.IterationData>();
                List<?> toolCallsPerIter = null;
                if (result.metrics() != null) {
                    Object tcpi = result.metrics().get("toolCallsPerIteration");
                    if (tcpi instanceof List<?>) toolCallsPerIter = (List<?>) tcpi;
                }
                for (int i = 0; i < result.usagePerIteration().size(); i++) {
                    var usage = result.usagePerIteration().get(i);
                    var iter = new AgentObservabilityRequest.IterationData();
                    iter.setIterationNumber(i);
                    iter.setPromptTokens(usage.promptTokens() != null ? usage.promptTokens() : 0);
                    iter.setCompletionTokens(usage.completionTokens() != null ? usage.completionTokens() : 0);
                    if (usage.cacheCreationInputTokens() != null) iter.setCacheCreationTokens(usage.cacheCreationInputTokens());
                    if (usage.cacheReadInputTokens() != null) iter.setCacheReadTokens(usage.cacheReadInputTokens());
                    if (usage.cachedTokens() != null) iter.setCachedTokens(usage.cachedTokens());
                    if (usage.reasoningTokens() != null) iter.setReasoningTokens(usage.reasoningTokens());
                    if (result.iterationDurations() != null && i < result.iterationDurations().size()) {
                        iter.setDurationMs(result.iterationDurations().get(i));
                    }
                    if (result.finishReasonsPerIteration() != null && i < result.finishReasonsPerIteration().size()) {
                        iter.setFinishReason(result.finishReasonsPerIteration().get(i));
                    }
                    if (toolCallsPerIter != null && i < toolCallsPerIter.size()) {
                        Object count = toolCallsPerIter.get(i);
                        if (count instanceof Number n) iter.setToolCallCount(n.intValue());
                    }
                    iterations.add(iter);
                }
                request.setIterations(iterations);
            }

            // Record directly via local service (no HTTP)
            observabilityService.recordFromRequest(request);

        } catch (Exception e) {
            log.warn("[SUB_AGENT] Failed to record observability for '{}': {}", entity.getName(), e.getMessage());
        }
    }

    // ==================== Redis Events ====================

    private void publishSubAgentEvent(String parentConversationId, String eventType,
                                       String agentName, String avatarUrl, String agentIdStr) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", eventType);
            Map<String, Object> subAgentMeta = new LinkedHashMap<>();
            subAgentMeta.put("name", agentName);
            if (avatarUrl != null) subAgentMeta.put("avatarUrl", avatarUrl);
            if (agentIdStr != null) subAgentMeta.put("agentId", agentIdStr);
            event.put("subAgent", subAgentMeta);
            event.put("timestamp", Instant.now().toString());

            String json = objectMapper.writeValueAsString(event);
            eventBus.publish("ws:conversation:" + parentConversationId, json);
        } catch (Exception e) {
            log.warn("[SUB_AGENT] Failed to publish {} to parent: {}", eventType, e.getMessage());
        }
    }

    // ==================== Credential Helpers ====================

    private Map<String, Object> buildSubAgentCredentials(Map<String, Object> parentCreds,
                                                          int currentDepth, UUID subAgentEntityId) {
        Map<String, Object> creds = new HashMap<>();
        creds.put("__agent_depth__", currentDepth + 1);
        creds.put("turnId", UUID.randomUUID().toString());
        creds.put("__agentId__", subAgentEntityId.toString());

        // Cascade caller chain (§4.2 AGENT_BUDGET_HIERARCHY.md). The child must know the
        // full nearest-first ancestor chain so that when IT spawns a grandchild, the
        // reservation cascades all the way up to the root. Chain is empty on root spawns
        // (parent has no __agentId__).
        UUID parentAgentId = getCallerAgentEntityId(parentCreds);
        if (parentAgentId != null) {
            creds.put(CredentialsCallerChain.KEY,
                CredentialsCallerChain.forChild(parentCreds, parentAgentId));
        }

        // Forward parent restrictions
        if (parentCreds != null) {
            forwardCredential(creds, parentCreds, "allowedAgentIds");
            forwardCredential(creds, parentCreds, "__allowedToolIds__");
            forwardCredential(creds, parentCreds, "__allowedWorkflowIds__");
            forwardCredential(creds, parentCreds, "__allowedApplicationIds__");
            forwardCredential(creds, parentCreds, "__allowedTableIds__");
            forwardCredential(creds, parentCreds, "__allowedInterfaceIds__");
            forwardCredential(creds, parentCreds, "__allowedFileIds__");
            forwardCredential(creds, parentCreds, "__orgId__");
            forwardCredential(creds, parentCreds, "__orgRole__");
            forwardCredential(creds, parentCreds, "__workflowRunId__");
            // Hosting workflow NODE - keeps the browser-agent live view of a
            // sub-agent's agent_browse addressed to the host builder node.
            forwardCredential(creds, parentCreds, "__workflowNodeId__");
            // Forward tool callback URL for conversation-local tools
            forwardCredential(creds, parentCreds, "__toolCallbackUrl__");
            // Forward approved services
            forwardCredential(creds, parentCreds, "__approvedServices__");
            // Forward task linkage for execution→task tracing
            forwardCredential(creds, parentCreds, "__taskId__");
            // The parent's read/write MODES. ToolAccessControl reads an absent mode as FULL
            // access, so without this a read-only mail agent's child could SEND: spawning a
            // child was the way around the one restriction no approval wildcard can lift.
            //
            // BOTH spellings, because the producers disagree and each one covers a different
            // origin: AgentContextBuilder writes the namespaced form for a conversation, while
            // AgentToolsConfigCredentials writes the plain form for a sub-agent's OWN config
            // and for every CLI-bridge session. Forwarding one would carry a restriction that
            // came from chat and silently drop one that came from the agent's own row, which
            // is the deeper of the two nestings and the likelier to matter.
            //
            // Note this NARROWS: a child that states no mode for a family now inherits the
            // parent's instead of defaulting to write. That is the point, and it is the safe
            // direction, but it is a real behaviour change for children created through the
            // `agent` tool, which routinely omits modes.
            //
            // Deliberately NOT forwarding the module list: the block above resolves the
            // child's own modules and overwrites anything put here, and its comment explains
            // why inheriting them would be wrong in both directions.
            for (String key : ToolAccessControl.ACCESS_MODE_KEYS) {
                forwardCredential(creds, parentCreds, key);
            }
            for (String key : ToolAccessControl.INTERNAL_ACCESS_MODE_KEYS) {
                forwardCredential(creds, parentCreds, key);
            }
        }

        return creds;
    }

    private void forwardCredential(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * PR20 - read X-Organization-ID from the inbound HTTP request that triggered
     * this sub-agent dispatch (PR16 forwarders set the header on the caller).
     * Returns null when there is no request context (purely async sub-agent
     * dispatch - falls through to personal scope on the observability row,
     * matches the strict-isolation default for un-tagged executions).
     */
    private static String resolveOrganizationId(Map<String, Object> credentials) {
        String requestOrgId = TenantResolver.currentRequestOrganizationId();
        if (requestOrgId != null) {
            return requestOrgId;
        }
        if (credentials == null) {
            return null;
        }
        Object credentialOrgId = credentials.get("__orgId__");
        if (credentialOrgId instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    private static String resolveOrganizationRole(Map<String, Object> credentials) {
        if (credentials == null) {
            return null;
        }
        Object credentialOrgRole = credentials.get("__orgRole__");
        if (credentialOrgRole instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    private void applyToolsConfigCredentials(Map<String, Object> credentials, Map<String, Object> toolsConfig) {
        // Delegates to the shared agent-service builder (write-side counterpart of
        // ToolAccessControl.getAllowedIds) so the sub-agent cascade and the CLI-bridge
        // session (CliAgentService) emit identical allow-list/access-mode credentials.
        // A null toolsConfig is treated as an empty config (deny-all for the 5 internal
        // resources) - a sub-agent must never fall through to full tenant access.
        AgentToolsConfigCredentials.apply(credentials, toolsConfig);
    }

    // ==================== Parameter Helpers ====================

    private int getAgentDepth(Map<String, Object> credentials) {
        if (credentials == null) return 0;
        Object depth = credentials.get("__agent_depth__");
        if (depth instanceof Number num) return num.intValue();
        if (depth instanceof String str) {
            try { return Integer.parseInt(str); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<String> getAllowedAgentIds(Map<String, Object> credentials) {
        return ToolAccessControl.getAllowedIds(credentials, "agent");
    }

    private String getConversationId(Map<String, Object> credentials) {
        if (credentials == null) return null;
        Object val = credentials.get("conversationId");
        return val instanceof String s ? s : null;
    }

    private String getTurnId(Map<String, Object> credentials) {
        if (credentials == null) return null;
        Object val = credentials.get("turnId");
        return val instanceof String s ? s : null;
    }

    private String getWorkflowRunId(Map<String, Object> credentials) {
        if (credentials == null) return null;
        Object val = credentials.get("__workflowRunId__");
        return val instanceof String s ? s : null;
    }

    private UUID getCallerAgentEntityId(Map<String, Object> credentials) {
        if (credentials == null) return null;
        Object agentId = credentials.get("__agentId__");
        if (agentId instanceof String s && !s.isBlank()) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }

    private String getStringParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val != null ? val.toString() : null;
    }

    private Integer getIntParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /**
     * Build the sub-agent's {@link AgentLoopContext}. Called on the EXECUTION pair for the
     * primary attempt, and on the BILLED pair by the execution-link bridge-failure fallback in
     * {@link #doExecute} - {@code provider}/{@code model}/{@code credentials} are the only
     * fields that differ between the two calls; {@code maxTokens}/{@code contextWindow}/
     * {@code reasoningEffort} are re-resolved against whichever pair is passed in, so the
     * fallback never sends the execution pair's limits to the billed pair's provider.
     */
    private AgentLoopContext buildSubAgentContext(String provider, String model, Map<String, Object> credentials,
            String fullSystemPrompt, String fullPrompt, List<Message> conversationHistory,
            List<ToolDefinition> tools, AgentEntity entity, int timeoutSeconds, PreIterationGuard guard,
            String userRoles, String tenantId, String executionId) {
        return AgentLoopContext.builder()
            .provider(provider)
            .model(model)
            .systemPrompt(fullSystemPrompt)
            .userPrompt(fullPrompt)
            .conversationHistory(conversationHistory)
            .tools(tools)
            .autoDiscoverTools(false) // tools come from cache, not discovery
            .maxIterations(resolveMaxIterations(entity))
            .executionTimeout(timeoutSeconds)
            // Clamp the configured budget (per-agent value, else the platform default)
            // to the model's real output ceiling so a high default (e.g. 16000) never
            // 400s against a low-cap model (DeepSeek-chat = 8192). Unknown cap ⇒
            // MaxTokensClamp's safe 8192 floor.
            .maxTokens(com.apimarketplace.agent.config.MaxTokensClamp.clamp(
                    entity.getMaxTokens() != null ? entity.getMaxTokens() : agentDefaults.getMaxTokens(),
                    modelCatalog != null ? modelCatalog.resolveMaxOutputTokens(provider, model) : null))
            // Resolved on whichever pair actually runs the turn (the execution pair for the
            // primary attempt, the billed pair for the fallback) - the window that can
            // overflow belongs to that model. Observability only.
            .contextWindow(modelCatalog != null ? modelCatalog.resolveContextWindow(provider, model) : null)
            .temperature(entity.getTemperature() != null ? entity.getTemperature().doubleValue() : 0.7)
            .tenantId(tenantId)
            .keyRoute(childKeyRoute(credentials, tenantId, provider))
            // The caller's roles, carried in the parent's credentials by the same key every
            // other consumer reads. Without them the loop's bridge gate sees null, treats it
            // as USER, and refuses an ADMIN their own sub-agent on a CLI bridge. Blank
            // normalises to null so downstream reads one spelling.
            .userRoles(userRoles)
            .agentId(entity.getId() != null ? entity.getId().toString() : null)
            // Carry the spawned execution's id so CE centralized relay billing aggregates the
            // sub-agent's relayed calls into ONE CE_LLM_RELAY line (parity with the main loop);
            // without it the sub-agent silently falls back to per-call billing.
            .executionId(executionId)
            .credentials(credentials)
            .preIterationGuard(guard)
            // Per-agent LoopDetector thresholds - null ⇒ AgentLoopService.bootstrapLoop
            // falls back to LoopDetector defaults.
            .loopIdenticalStop(entity.getLoopIdenticalStop())
            .loopConsecutiveStop(entity.getLoopConsecutiveStop())
            // Reasoning effort (bridge/CLI providers): agent setting, then the
            // per-model admin default. No per-conversation override on the
            // sub-agent path. Null modelCatalog (e.g. unit tests) ⇒ agent value only.
            // An EXECUTION concern (the CLI/provider that actually runs the turn), so it
            // follows whichever pair is passed in.
            .reasoningEffort(modelCatalog != null
                ? modelCatalog.resolveEffortWithDefault(entity.getReasoningEffort(), provider, model)
                : entity.getReasoningEffort())
            .purpose(CallPurpose.MAIN)
            .build();
    }

    private int resolveMaxIterations(AgentEntity entity) {
        if (entity.getMaxIterations() != null) return entity.getMaxIterations();
        if (entity.getConfig() != null) {
            Object maxIter = entity.getConfig().get("maxIterations");
            if (maxIter instanceof Number num) return num.intValue();
        }
        return DEFAULT_MAX_ITERATIONS;
    }

    private int clampTimeout(int timeout) {
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, timeout));
    }

    private String truncateResponse(String response) {
        if (response == null) return null;
        if (response.length() <= MAX_RESPONSE_LENGTH) return response;
        int truncated = response.length() - MAX_RESPONSE_LENGTH;
        return response.substring(0, MAX_RESPONSE_LENGTH) + "... [truncated " + truncated + " chars]";
    }

    private List<String> extractToolNames(AgentLoopResult result) {
        if (result.toolResults() == null || result.toolResults().isEmpty()) return List.of();
        return result.toolResults().stream()
            .filter(tr -> tr.toolCall() != null)
            .map(tr -> tr.toolCall().toolName() != null ? tr.toolCall().toolName() : "unknown")
            .distinct()
            .collect(Collectors.toList());
    }

    // ==================== Task Delegation Helper ====================

    /**
     * Returns just the task delegation summary section (no base prompt).
     * Used when the modular prompt is built separately.
     */
    private String getTaskSummarySection(AgentEntity entity, String tenantId) {
        if (agentTaskService == null || entity.getId() == null || tenantId == null) {
            return null;
        }
        try {
            java.time.Instant since = java.time.Instant.now().minus(java.time.Duration.ofHours(24));
            com.apimarketplace.agent.dto.TaskSummaryResponse summary =
                agentTaskService.getTaskSummaryForPrompt(tenantId, entity.getId(), since);
            if (summary == null || !summary.hasTasks()) {
                return null;
            }
            return summary.toPromptSection();
        } catch (Exception e) {
            log.debug("Failed to get task summary section for agent {}: {}", entity.getId(), e.getMessage());
            return null;
        }
    }

    // ==================== Error Helpers ====================

    private ToolResult buildFailure(ToolCall toolCall, long startTime, String error) {
        long duration = System.currentTimeMillis() - startTime;
        return buildErrorResult(toolCall, error, duration);
    }

    private ToolResult buildErrorResult(ToolCall toolCall, String error, long duration) {
        return ToolResult.builder()
            .toolCall(toolCall)
            .success(false)
            .error(error)
            .durationMs(duration)
            .build();
    }
}
