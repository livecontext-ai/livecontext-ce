package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.client.dto.execution.*;
import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.TeeStreamingCallback;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for executing agents, classify, and guardrail operations remotely.
 * This runs in agent-service and receives requests from orchestrator via HTTP.
 *
 * For full agent execution: delegates to AgentLoopService (shared-agent-lib).
 * Classify/guardrail have their own services that also delegate to AgentLoopService.
 * Tool calls are delegated back to orchestrator via RemoteToolExecutionService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRemoteExecutionService {

    private final AgentLoopService agentLoopService;
    private final ObjectMapper objectMapper;
    private final RedisStreamingCallback redisStreamingCallback;
    private final ConversationRedisStreamingCallback conversationRedisStreamingCallback;
    private final CoreToolsCache coreToolsCache;

    /**
     * CE→cloud web-search relay gate. Per-tenant runtime filter for the
     * auto-discover (workflow agent) path: with the local websearch engine
     * disabled, web_search is only injected for tenants cloud-linked with the
     * CLOUD source. Field-injected and optional so unit tests and cloud
     * deployments are unaffected (null ⇒ no filtering).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.cloud.CeWebSearchRelayGate webSearchRelayGate;

    /**
     * Builds the workspace's long-term memory block appended to every system
     * prompt dispatched from here.
     *
     * <p>Field-injected rather than added to the {@code @RequiredArgsConstructor}
     * list on purpose: this class is constructed positionally in a dozen unit
     * tests, and widening the constructor would break every one of them over a
     * dependency they do not exercise. Optional, so a test that never wires it
     * gets a null and the guarded call below leaves the prompt untouched - the
     * same shape {@code webSearchRelayGate} above already uses.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.memory.MemoryPromptSection memoryPromptSection;
    private final AgentActivityPublisher agentActivityPublisher;
    private final GuardChainFactory guardChainFactory;
    private final ClassifyService classifyService;
    private final GuardrailService guardrailService;
    private final BridgeLoopDispatcher bridgeDispatcher;
    private final com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;

    /**
     * Liveness heartbeats for BRIDGE conversation runs. The in-process loop gets its
     * {@code stream:hb:{streamId}} key from {@link ConversationRedisStreamingCallback};
     * a bridge run has no local callback (the bridge publishes to Redis itself), yet its
     * conversation {@code Stream} DB row exists with a frozen {@code updated_at} - so
     * without a heartbeat the conversation-service absolute-timeout reaper interrupts a
     * HEALTHY bridge run at the TTL (~10 min) even while it is actively streaming.
     * {@link #executeAgentViaBridge} registers the stream for the duration of the dispatch.
     */
    private final ActiveStreamRegistry activeStreamRegistry;

    /**
     * Model execution links (CLOUD only): resolves whether a billed
     * {@code (provider, model)} pair must be EXECUTED through a CLI bridge (or
     * another API provider) while keeping the billed identity for observability +
     * credit consumption. Shared with the classify, guardrail and sub-agent paths
     * so all four honour a link identically; in CE, or with the feature off, it
     * returns no route and the billed model runs on its own provider as usual.
     *
     * <p>The router is an unconditional bean, so the null guard below only covers a unit
     * test that constructs this service directly.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExecutionLinkRouter executionLinkRouter;

    /**
     * Pins whose API key each execution runs on (see {@link KeyRouteResolver}). Optional
     * for the same reason as the router: a unit test constructing this service directly
     * leaves it null, and the context is then unpinned (pre-pin behaviour).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KeyRouteResolver keyRouteResolver;

    /**
     * Key under which the execution's {@code KeyRoute} rides on the response's
     * {@code metrics} map. Read by the orchestrator (workflow agents) and
     * conversation-service (chat) when they report observability, which is where the
     * credit debit happens.
     */
    public static final String KEY_ROUTE_METRIC = "keyRoute";

    /**
     * Optional Prometheus metrics sink. Field-injected for the same reason as the other
     * optional collaborators above (a dozen unit tests construct this service positionally).
     * Only consulted by the execution-link bridge-failure fallback below, to keep a
     * silent-by-design recovery visible to operators.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.metrics.AgentPrometheusMetrics prometheusMetrics;

    // ========== Full Agent Execution ==========

    /**
     * Back-compat overload - async paths (queue worker, recovery) call without
     * an inbound role context. Bridge-policy evaluation downstream will treat a
     * null role string as the default {@code USER}; admin-only policies must
     * use the explicit {@link #executeAgent(AgentExecutionRequestDto, String)}
     * overload from the sync HTTP path.
     */
    public AgentExecutionResponseDto executeAgent(AgentExecutionRequestDto request) {
        return executeAgent(request, null);
    }

    /**
     * Execute a full agent with tool calls and streaming.
     *
     * @param userRoles caller's role string (comma-separated, forwarded from
     *                  the inbound {@code X-User-Roles} header). Threaded
     *                  through to the bridge access guard so admin-only
     *                  policies recognise ADMIN dispatches. {@code null} is
     *                  treated as the default USER role.
     */
    public AgentExecutionResponseDto executeAgent(AgentExecutionRequestDto request, String userRoles) {
        long startTime = System.currentTimeMillis();
        // Normalise provider against the catalog FIRST: a bridge (CLI) model
        // stored as provider="anthropic" (frontend heuristic / LLM-authored
        // plan) must resolve to its bridge slug so shouldDispatch routes it via
        // the bridge AND it passes through BridgeAccessGuard - identical to the
        // chat path. No-op for valid pairs (incl. deliberate direct-API choices).
        request = request.withProvider(
            modelCatalogService.resolveProvider(request.provider(), request.model()));
        String agentEntityId = request.agentEntityId();

        // ── Long-term memory ──────────────────────────────────────────────────
        // THE injection point. Every direct-API execution in the product arrives
        // here - chat (conversation-service dispatches remotely), the workflow
        // agent node, task and schedule runs - and the split between the bridge
        // transport and the direct loop happens BELOW, so appending here covers
        // both without a second call site to keep in step. The one execution path
        // that does not pass through is the sub-agent handler, which appends the
        // same block itself; MemoryInjectionCallsiteInvariantTest pins both.
        //
        // The org id comes from the thread binding rather than the DTO because the
        // controller already wraps this call in runWithOrgScope: reading it here means
        // memory resolves in the SAME workspace as the rest of the run, instead of a
        // second one carried separately in the payload that could drift from it.
        // (The binding is not un-spoofable and is not claimed to be: the controller
        // fills it from the gateway header when there is one and from the request's
        // own credentials for an internal caller. The property is consistency.)
        if (memoryPromptSection != null) {
            try {
                request = request.withSystemPrompt(memoryPromptSection.appendTo(
                    request.systemPrompt(),
                    com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId(),
                    com.apimarketplace.agent.memory.MemoryAgentScope.agentIdOrNull(agentEntityId)));
            } catch (RuntimeException memoryUnavailable) {
                // The run continues WITHOUT memory rather than failing. Memory is an
                // enrichment: an agent that runs without it is degraded, an agent that
                // 500s because a lookup misbehaved is broken, and this is the site every
                // direct-API execution in the product goes through. render() already
                // swallows its own failures, so reaching here means something further
                // out (a proxy, a advice, a future refactor of appendTo) threw - which
                // is exactly the case a guard is for. Same policy as the bridge site in
                // conversation-service.
                log.warn("[MEMORY] Prompt enrichment failed for agent {}; running without it: {}",
                    agentEntityId, memoryUnavailable.toString());
            }
        }
        // Prefer the dispatcher-minted executionId so WS payloads, MCP credentials, and
        // the persisted agent_executions.id share the same UUID. Falls back to a local
        // mint only when the upstream caller hasn't been updated yet (e.g. legacy
        // orchestrator AgentNode dispatches before the executionId DTO field rollout).
        String executionId = request.executionId() != null && !request.executionId().isBlank()
                ? request.executionId()
                : Optional.ofNullable(extractExecutionId(request.credentials())).orElseGet(() -> UUID.randomUUID().toString());

        // Scope fleet activity to a task when the caller injected __taskId__ via credentials.
        // Workflow agent nodes don't set this; only task-delegation paths do.
        String taskId = extractTaskId(request.credentials());

        // Model execution link (CLOUD only): a billed (provider, model) may EXECUTE on a
        // different target - a CLI bridge (claude-code, ...) OR a regular API provider (e.g.
        // openrouter) - while the billed identity is kept for billing. Resolved independently
        // of the bridge transport (a link to a non-bridge provider doesn't need the bridge).
        // A link can be scoped to one app surface, so resolve passes this run's activity
        // source (CHAT / WORKFLOW / WEBHOOK / ...): an exact-surface link wins, else the ALL
        // wildcard. The service bean is absent in CE, so this is inert there.
        // The router already drops a bridge-targeted link when the bridge transport is not
        // wired here, so what comes back is always runnable (never a silent bridge fail).
        ModelExecutionLinkService.ExecutionRoute executionRoute = executionLinkRouter != null
            ? executionLinkRouter.runnableRoute(request.provider(), request.model(), resolveActivitySource(request))
            : null;

        // Execution identity (where the run actually goes; = billed identity unless a link
        // redirected it). executeAgentViaBridge/executeAgentDirect each recompute the full
        // execProvider/execModel pair themselves from executionRoute - this one is only to
        // decide which of the two to call.
        final String execProvider = executionRoute != null ? executionRoute.executionProvider() : request.provider();

        // Bridge path iff the EXECUTION provider is a CLI bridge (and the bridge is wired).
        // executeAgentViaBridge consumes the route (exec target + billed relabel + restricted
        // toolset); a direct (non-link) bridge run passes a null route (no swap/relabel/restrict).
        if (bridgeDispatcher.shouldDispatch(execProvider)) {
            return executeAgentViaBridge(request, executionRoute, startTime, agentEntityId, executionId, taskId, userRoles);
        }

        // Otherwise the run goes through the direct agent loop.
        return executeAgentDirect(request, executionRoute, startTime, agentEntityId, executionId, taskId, false);
    }

    /**
     * Run the direct (non-bridge) agent loop for either of two callers:
     * <ol>
     *   <li>the normal path from {@link #executeAgent}, when the execution target is not a
     *       CLI bridge - {@code skipStartedEvent=false}, this publishes fleet activity
     *       "started" itself;</li>
     *   <li>the execution-link bridge-failure fallback from {@link #dispatchToBridge}, when
     *       a linked bridge dispatch failed before producing any visible output -
     *       {@code skipStartedEvent=true} because {@link #executeAgentViaBridge} already
     *       published "started" for this logical execution before the (failed) bridge
     *       attempt, and {@code executionRoute} is passed as {@code null} since {@code request}
     *       already carries the billed identity verbatim (nothing to re-stamp).</li>
     * </ol>
     *
     * <p>When a link targets a regular API provider, {@code loopRequest} carries the
     * EXECUTION identity and the result is re-stamped with the billed identity (relabel
     * after {@link #convertToResponseDto}). The direct loop exposes ONLY platform MCP
     * tools, so no extra "API mode" restriction is needed.
     */
    private AgentExecutionResponseDto executeAgentDirect(AgentExecutionRequestDto request,
                                                          ModelExecutionLinkService.ExecutionRoute executionRoute,
                                                          long startTime,
                                                          String agentEntityId,
                                                          String executionId,
                                                          String taskId,
                                                          boolean skipStartedEvent) {
        final String execProvider = executionRoute != null ? executionRoute.executionProvider() : request.provider();
        final String execModel = executionRoute != null ? executionRoute.executionModel() : request.model();
        final AgentExecutionRequestDto loopRequest = executionRoute != null
            ? request.withExecutionTarget(execProvider, execModel)
            : request;

        // Hoisted out of the try so the catch blocks can finalize the stream on
        // failure: without it an exception escaping the loop left the callback
        // registered forever (heartbeat refreshed for the pod's lifetime), which
        // the absolute-timeout reconciler can no longer clean since it skips
        // live-heartbeat streams.
        ConversationRedisStreamingCallback.ConversationCallback conversationCallback = null;
        try {
            // Publish fleet activity: execution started. Skipped on the bridge-failure
            // fallback path - executeAgentViaBridge already published it for this
            // logical execution before the (failed) bridge attempt.
            if (!skipStartedEvent) {
                String source = resolveActivitySource(request);
                agentActivityPublisher.publishExecutionStarted(
                    agentEntityId, executionId, request.model(), source, taskId);
            }

            // Build the guard chain: tenant budget (macro) → agent budget (micro).
            // Tenant always wins because it's chained first; if the tenant runs out of credits
            // there is no point checking the agent budget.
            PreIterationGuard guard = buildGuardChain(request);

            // Convert DTO to shared-agent-lib context (includes guard). loopRequest carries the
            // EXECUTION identity (= billed identity unless a link to a regular API provider
            // redirected it); fleet activity + streaming callbacks below keep the BILLED model
            // for display.
            AgentLoopContext context = convertToContext(loopRequest, guard, executionId);

            // Create streaming callback based on format, wrapped with fleet activity publishing
            StreamingCallback callback = null;
            StreamingCallback originalCallback = null;
            if ("conversation".equals(request.streamingFormat()) && request.streamChannelId() != null) {
                // Conversation format: flat events on stream:events:{streamId} + ws:conversation:{conversationId}
                conversationCallback = conversationRedisStreamingCallback.forExecution(
                    request.streamChannelId(), request.conversationId(), request.model(),
                    request.parentConversationId(), request.subAgentName(),
                    request.subAgentAvatarUrl(), request.subAgentId(),
                    request.workflowRunId());
                originalCallback = conversationCallback;
                // Workflow agent NODE with a user-facing conversation: tee the workflow-envelope
                // callback alongside, so the run view keeps its tool events on
                // ws:workflow:run:{runId} (with the nodeId/itemIndex/iteration routing keys)
                // while the conversation panel streams live. The two formats used to be
                // mutually exclusive, forcing the async queue path to sacrifice the live
                // conversation transcript to keep the run view fed.
                if (request.nodeId() != null && request.workflowRunId() != null) {
                    StreamingCallback workflowCallback = redisStreamingCallback.forExecution(
                        request.workflowRunId(), request.nodeId(),
                        request.itemIndex(), request.loopIteration());
                    originalCallback = new TeeStreamingCallback(conversationCallback, workflowCallback);
                }
                callback = wrapWithActivityPublishing(originalCallback, agentEntityId, executionId, taskId);
            } else if (request.streamChannelId() != null) {
                // Workflow format (default): envelope events on ws:workflow:run:{runId}
                originalCallback = redisStreamingCallback.forExecution(
                    request.streamChannelId(), request.nodeId(),
                    request.itemIndex(), request.loopIteration());
                callback = wrapWithActivityPublishing(originalCallback, agentEntityId, executionId, taskId);
            } else if (agentEntityId != null) {
                // No streaming channel but fleet activity still needed
                callback = wrapWithActivityPublishing(null, agentEntityId, executionId, taskId);
            }

            // Execute via shared-agent-lib
            AgentLoopResult result = agentLoopService.execute(context, callback);

            // Publish done/error event - agentLoopService.execute() does NOT call onComplete/onError
            if (originalCallback != null) {
                if (result.success()) {
                    originalCallback.onComplete(result.response());
                } else if (result.error() != null) {
                    originalCallback.onError(result.error());
                } else {
                    originalCallback.onComplete(result.response());
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            // Publish fleet activity: execution completed
            int totalTokens = result.usage() != null ? result.usage().getTotal() : 0;
            int totalToolCalls = result.toolResults() != null ? result.toolResults().size() : 0;
            agentActivityPublisher.publishExecutionCompleted(
                agentEntityId, executionId,
                result.success() ? "COMPLETED" : "FAILED",
                totalTokens, totalToolCalls, duration, taskId);

            // Convert result to DTO, enriching with conversation callback data if available
            AgentExecutionResponseDto response = convertToResponseDto(result, duration, conversationCallback,
                context.keyRoute());
            // A link to a regular API provider executed on the EXECUTION identity; re-stamp the
            // BILLED identity so the orchestrator's observability + credit consumption (which read
            // the result's provider/model) charge the billed price, not the execution provider.
            if (executionRoute != null) {
                response = response.withBilledIdentity(request.provider(), request.model(),
                        executionRoute.executionProvider());
            }
            return response;

        } catch (BridgeAccessDeniedException e) {
            // Let the typed denial surface to GlobalExceptionHandler so the caller
            // receives 403/429 with the reason code. Must come BEFORE the Exception
            // catch-all, otherwise the denial is squashed into a generic 200/FAILED
            // response and the client cannot distinguish quota from misconfiguration.
            long duration = System.currentTimeMillis() - startTime;
            log.warn("Agent execution denied by bridge guard: provider={} reason={}",
                e.getProviderName(), e.getReason());
            agentActivityPublisher.publishExecutionCompleted(
                agentEntityId, executionId, "FAILED", 0, 0, duration, taskId);
            failStreamQuietly(conversationCallback, "Agent execution denied: " + e.getReason());
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Agent execution failed: {}", e.getMessage(), e);
            agentActivityPublisher.publishExecutionCompleted(
                agentEntityId, executionId, "FAILED", 0, 0, duration, taskId);
            failStreamQuietly(conversationCallback, "Agent execution error: " + e.getMessage());
            return new AgentExecutionResponseDto(
                false, null, null, List.of(), 0, Map.of(),
                "Agent execution error: " + e.getMessage(),
                duration, request.provider(), request.model(),
                List.of(), AgentStopReason.ERROR.name(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null
            );
        }
    }


    /**
     * Finalize the conversation stream on a failure that escaped the agent loop.
     * onError publishes the stream error, finalizes the stream in
     * conversation-service and unregisters from the drain registry - without
     * this the registry entry leaks and its heartbeat keeps the dead stream
     * alive forever (the reconciler skips live-heartbeat streams).
     */
    private static void failStreamQuietly(
            ConversationRedisStreamingCallback.ConversationCallback callback, String message) {
        if (callback == null) return;
        try {
            callback.onError(message);
        } catch (Exception e) {
            log.warn("Failed to finalize stream after execution failure: {}", e.getMessage());
        }
    }

    // ========== Bridge Execution ==========

    /**
     * Execute via the bridge server (CLI-based providers). The bridge publishes its own
     * streaming events to Redis using {@code streamChannelId} on the request, so no
     * local StreamingCallback wiring is needed. Budget enforcement is performed inside
     * the bridge using the DTO's {@code tenantBalance}/{@code maxCreditBudget} fields.
     *
     * <p>Fleet activity (started/completed) is still published so the agent card shimmers
     * for remote executions - same behaviour as the local path.
     */
    private AgentExecutionResponseDto executeAgentViaBridge(AgentExecutionRequestDto request,
                                                              ModelExecutionLinkService.ExecutionRoute executionRoute,
                                                              long startTime,
                                                              String agentEntityId,
                                                              String executionId,
                                                              String taskId,
                                                              String userRoles) {
        String source = resolveActivitySource(request);
        // request stays the BILLED identity (fleet activity + relabel reference). When a
        // link is present, dispatchRequest swaps in the CLI bridge EXECUTION target;
        // billing-facing fields are never touched. NB: the bridge's own in-run budget
        // guard (a coarse safety throttle) then prices the EXECUTION model; the
        // authoritative ledger debit downstream still uses the re-stamped billed
        // identity, so the bill the user sees is the billed model's price.
        // A linked run also forces the bridge into restricted "API mode": only the platform
        // MCP tools, empty cwd (no AGENTS.md/CLAUDE.md), no account/CLI leak - so nothing
        // reveals which CLI executed it. Direct (non-link) bridge runs stay unrestricted.
        AgentExecutionRequestDto dispatchRequest = executionRoute != null
            ? request.withExecutionTarget(executionRoute.executionProvider(), executionRoute.executionModel())
                     .withRestrictedToolset(true)
            : request;
        agentActivityPublisher.publishExecutionStarted(
            agentEntityId, executionId, request.model(), source, taskId);

        // Shield the conversation stream from the absolute-timeout reaper while this
        // worker is alive: a bridge run never saves the Stream row mid-run (chunks go
        // straight to Redis) and, without this registration, never has a stream:hb key,
        // so conversation-service would interrupt it at the TTL even mid-stream. The
        // drain handle only logs: the worker cannot rescue a bridge run's partial
        // content itself - on shutdown the heartbeat lapses (120s TTL) and the reaper
        // rescues the partials exactly as it did before this registration existed.
        // Same gating as the in-process path's ConversationCallback (conversation
        // format only: a workflow-format streamChannelId is a run channel, not a
        // conversation Stream row).
        final String heartbeatStreamId =
            "conversation".equals(request.streamingFormat()) && request.streamChannelId() != null
                ? request.streamChannelId()
                : null;
        if (heartbeatStreamId != null) {
            activeStreamRegistry.register(heartbeatStreamId, () ->
                log.warn("Bridge stream {} still in flight at shutdown drain expiry; " +
                    "conversation-service will rescue it once the heartbeat TTL lapses", heartbeatStreamId));
        }
        try {
            return dispatchToBridge(request, dispatchRequest, executionRoute, startTime,
                agentEntityId, executionId, taskId, userRoles);
        } finally {
            if (heartbeatStreamId != null) {
                activeStreamRegistry.unregister(heartbeatStreamId);
            }
        }
    }

    private AgentExecutionResponseDto dispatchToBridge(AgentExecutionRequestDto request,
                                                       AgentExecutionRequestDto dispatchRequest,
                                                       ModelExecutionLinkService.ExecutionRoute executionRoute,
                                                       long startTime,
                                                       String agentEntityId,
                                                       String executionId,
                                                       String taskId,
                                                       String userRoles) {
        AgentExecutionResponseDto response;
        try {
            log.info("Dispatching agent to bridge: billedProvider={}, billedModel={}, execProvider={}, execModel={}, linked={}, tenantId={}, streamChannel={}, conversation={}",
                request.provider(), request.model(), dispatchRequest.provider(), dispatchRequest.model(),
                executionRoute != null, request.tenantId(),
                request.streamChannelId(), request.conversationId());
            // Apply the per-model default reasoning effort as the lowest-precedence
            // fallback: the caller's value (per-conversation override > per-agent) wins;
            // otherwise the model's admin default applies. The workflow path can't read
            // the catalog (orchestrator has none), so this is where its model default
            // lands. Chat already resolved the default upstream → withReasoningEffort is
            // a no-op there (caller value already set). Resolved against the EXECUTION
            // target (the model the CLI actually runs) when a link redirected us.
            String effectiveEffort = modelCatalogService.resolveEffortWithDefault(
                dispatchRequest.reasoningEffort(), dispatchRequest.provider(), dispatchRequest.model());
            AgentExecutionRequestDto effectiveRequest = java.util.Objects.equals(effectiveEffort, dispatchRequest.reasoningEffort())
                ? dispatchRequest
                : dispatchRequest.withReasoningEffort(effectiveEffort);
            // executionRoute != null: an execution link put this run on the CLI. The caller
            // asked for the billed pair and is charged for it; refusing them the CLI they never
            // named is what left the migrated agents failing every 30 minutes.
            response = bridgeDispatcher.dispatchRaw(effectiveRequest, userRoles, executionRoute != null);
        } catch (BridgeAccessDeniedException e) {
            // Surface the typed denial so GlobalExceptionHandler maps it to 403/429
            // with the reason code. Catching it as generic Exception below would
            // squash it into a 200/FAILED response prefixed "Bridge agent execution
            // error: ..." - masking quota vs misconfiguration vs disabled-policy. NEVER
            // falls back even on a linked run: this is a deliberate admin policy/quota
            // decision (admin_only, exhausted daily cap), not a transport failure -
            // silently bypassing it onto the full-price direct API would defeat the
            // cost control the admin configured.
            long duration = System.currentTimeMillis() - startTime;
            log.warn("Bridge agent execution denied: provider={} reason={}",
                e.getProviderName(), e.getReason());
            agentActivityPublisher.publishExecutionCompleted(
                agentEntityId, executionId, "FAILED", 0, 0, duration, taskId);
            throw e;
        } catch (Exception e) {
            return handleBridgeFailurePreStream(request, dispatchRequest, executionRoute, startTime,
                agentEntityId, executionId, taskId,
                "Bridge agent execution error: " + e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - startTime;

        if (response == null) {
            return handleBridgeFailurePreStream(request, dispatchRequest, executionRoute, startTime,
                agentEntityId, executionId, taskId,
                "Bridge execution failed: no response from bridge server", null);
        }

        // A linked run that reached the bridge but failed before producing anything an end
        // user could see (no content, no tool results) is safe to retry invisibly: nothing
        // has been streamed yet (the bridge publishes to Redis as it goes, so ANY visible
        // output means this branch must not fire - see hasNoVisibleOutput). A non-link
        // (direct bridge selection) failure falls through unchanged below, exactly as before
        // this fallback existed - there is no distinct billed identity to retry on when the
        // billed pair already IS the bridge.
        // A run the user cancelled is never retried: re-running it on the direct API would
        // bill a full turn for a chat the user just stopped, invisibly.
        if (executionRoute != null && !response.success() && response.hasNoVisibleOutput()
                && !response.wasCancelledByUser()) {
            return handleBridgeFailurePreStream(request, dispatchRequest, executionRoute, startTime,
                agentEntityId, executionId, taskId,
                response.error() != null ? response.error() : "Bridge execution failed before producing output",
                null);
        }

        // Re-stamp the BILLED identity onto the response so the orchestrator's
        // observability + credit consumption (which read the result's provider/model
        // as authoritative) charge the billed model, not the CLI bridge it executed
        // on. No-op when no link redirected this run.
        if (executionRoute != null) {
            // The provider that produced the counts is the one we dispatched to, read back
            // from the request that ran rather than re-derived from the route: one fact,
            // one source.
            response = response.withBilledIdentity(request.provider(), request.model(),
                    dispatchRequest.provider());
        }

        int totalTokens = 0;
        if (response.totalUsage() != null) {
            Object total = response.totalUsage().get("totalTokens");
            if (total instanceof Number n) totalTokens = n.intValue();
        }
        int totalToolCalls = response.toolResults() != null ? response.toolResults().size() : 0;
        agentActivityPublisher.publishExecutionCompleted(
            agentEntityId, executionId,
            response.success() ? "COMPLETED" : "FAILED",
            totalTokens, totalToolCalls, duration, taskId);

        // A bridge holds no API key: the run is PLATFORM, said explicitly (like a CLI session
        // and a sub-agent bridge child) so the fleet view can split routes without guessing.
        return response.withMetric(KEY_ROUTE_METRIC, KeyRoute.PLATFORM.name());
    }

    /**
     * A bridge dispatch failed before producing anything an end user could see (transport
     * exception, no response, or a response with empty content/finalResponse/toolResults).
     * When {@code executionRoute} is non-null (a model execution link caused this dispatch),
     * silently retries the SAME billed request on its direct API instead of surfacing the
     * failure - the retry is invisible because nothing was ever shown. Otherwise (a direct,
     * non-link bridge selection) there is no distinct billed identity to retry on, so this
     * reproduces the original failure response exactly as before this fallback existed.
     *
     * @param cause the transport exception, or {@code null} when the bridge returned cleanly
     *              (null response, or a response with no visible output)
     */
    private AgentExecutionResponseDto handleBridgeFailurePreStream(
            AgentExecutionRequestDto request,
            AgentExecutionRequestDto dispatchRequest,
            ModelExecutionLinkService.ExecutionRoute executionRoute,
            long startTime, String agentEntityId, String executionId, String taskId,
            String failureMessage, Exception cause) {
        if (executionRoute != null) {
            log.warn("[EXECUTION_LINK_FALLBACK] bridge dispatch for billed={}/{} (exec={}/{}) "
                    + "failed before producing output ({}); retrying on the billed pair's direct API",
                request.provider(), request.model(), dispatchRequest.provider(), dispatchRequest.model(),
                cause != null ? cause.toString() : failureMessage);
            if (prometheusMetrics != null) {
                prometheusMetrics.recordExecutionLinkFallback(
                    request.provider(), request.model(), dispatchRequest.provider());
            }
            // executionRoute=null: request already carries the billed identity verbatim,
            // nothing to re-stamp. skipStartedEvent=true: executeAgentViaBridge already
            // published "started" for this logical execution before the failed attempt.
            return executeAgentDirect(request, null, startTime, agentEntityId, executionId, taskId, true);
        }

        long duration = System.currentTimeMillis() - startTime;
        if (cause != null) {
            log.error("Bridge agent execution failed: {}", cause.getMessage(), cause);
        } else {
            log.error("{} (provider={}, model={})", failureMessage, request.provider(), request.model());
        }
        agentActivityPublisher.publishExecutionCompleted(
            agentEntityId, executionId, "FAILED", 0, 0, duration, taskId);
        // A bridge that crashed before streaming still ran as a bridge: PLATFORM, said
        // explicitly, so no bridge row is ever left unpinned (zero tokens, zero debit).
        return new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(),
            failureMessage,
            duration, request.provider(), request.model(),
            List.of(), AgentStopReason.ERROR.name(),
            Map.of(KEY_ROUTE_METRIC, KeyRoute.PLATFORM.name()), List.of(), List.of(), List.of(),
            List.of(), List.of(), null
        );
    }

    // ========== Queue-Based Execution Router ==========

    /**
     * Routes execution to the appropriate service based on agent type.
     * Used by {@link AgentQueueWorkerService} to execute dequeued tasks.
     *
     * @param agentType the type of execution: "agent", "classify", or "guardrail"
     * @param requestPayload the serialized JSON request payload
     * @return serialized JSON result string
     * @throws IllegalArgumentException if agentType is unknown
     * @throws JsonProcessingException if serialization/deserialization fails
     */
    public String executeByType(String agentType, String requestPayload) throws JsonProcessingException {
        return executeByType(agentType, requestPayload, null);
    }

    public String executeByType(String agentType, String requestPayload, String userRoles) throws JsonProcessingException {
        return switch (agentType) {
            case AgentExecutionTask.TYPE_AGENT -> {
                AgentExecutionRequestDto request = objectMapper.readValue(
                    requestPayload, AgentExecutionRequestDto.class);
                AgentExecutionResponseDto response = executeAgent(request, userRoles);
                yield objectMapper.writeValueAsString(response);
            }
            case AgentExecutionTask.TYPE_CLASSIFY -> {
                ClassifyRequestDto request = objectMapper.readValue(
                    requestPayload, ClassifyRequestDto.class);
                ClassifyResponseDto response = classifyService.execute(request, userRoles);
                yield objectMapper.writeValueAsString(response);
            }
            case AgentExecutionTask.TYPE_GUARDRAIL -> {
                GuardrailRequestDto request = objectMapper.readValue(
                    requestPayload, GuardrailRequestDto.class);
                GuardrailResponseDto response = guardrailService.execute(request, userRoles);
                yield objectMapper.writeValueAsString(response);
            }
            default -> throw new IllegalArgumentException("Unknown agent type: " + agentType);
        };
    }

    // ========== Fleet Activity Wrapper ==========

    /**
     * Wraps a StreamingCallback to additionally publish fleet activity events
     * (tool_call_started / tool_call_completed) to the agent:activity channel.
     * If delegate is null, only publishes fleet events (no streaming).
     */
    private StreamingCallback wrapWithActivityPublishing(StreamingCallback delegate,
                                                          String agentEntityId, String executionId, String taskId) {
        if (agentEntityId == null && delegate != null) return delegate;
        if (agentEntityId == null) return null;

        return new StreamingCallback() {
            @Override
            public void onChunk(String content) {
                if (delegate != null) delegate.onChunk(content);
            }

            @Override
            public void onThinking(String thinking) {
                if (delegate != null) delegate.onThinking(thinking);
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                // Publish fleet event BEFORE delegate - ensures observability even if delegate throws
                agentActivityPublisher.publishToolCallStarted(
                    agentEntityId, executionId, toolCall.toolName(), toolCall.id(), taskId);
                if (delegate != null) delegate.onToolCall(toolCall);
            }

            @Override
            public void onToolResult(ToolResult result) {
                String toolName = result.toolCall() != null ? result.toolCall().toolName() : null;
                String toolCallId = result.toolCall() != null ? result.toolCall().id() : null;
                agentActivityPublisher.publishToolCallCompleted(
                    agentEntityId, executionId, toolName, toolCallId,
                    result.success(), result.durationMs(), taskId);
                if (delegate != null) delegate.onToolResult(result);
            }

            @Override
            public void onComplete(CompletionResponse response) {
                if (delegate != null) delegate.onComplete(response);
            }

            @Override
            public void onError(String error) {
                if (delegate != null) delegate.onError(error);
            }

            @Override
            public boolean shouldStop() {
                return delegate != null && delegate.shouldStop();
            }
        };
    }

    /**
     * Extracts {@code __taskId__} from a credentials map if present and non-blank.
     * Returns null otherwise - fleet activity then publishes without a taskId, which
     * means no card-level shimmer scoping (the agent is running outside a task).
     */
    private String extractTaskId(Map<String, Object> credentials) {
        if (credentials == null) return null;
        Object raw = credentials.get("__taskId__");
        if (raw == null) return null;
        String s = raw.toString();
        return s.isBlank() ? null : s;
    }

    private String resolveActivitySource(AgentExecutionRequestDto request) {
        if (request.source() != null && !request.source().isBlank()) {
            return request.source().trim();
        }
        return "conversation".equals(request.streamingFormat()) ? "CONVERSATION" : "WORKFLOW";
    }

    /**
     * Extracts {@code __executionId__} from legacy credentials if the DTO field
     * is absent. New callers should use {@link AgentExecutionRequestDto#executionId()}.
     */
    private String extractExecutionId(Map<String, Object> credentials) {
        if (credentials == null) return null;
        Object raw = credentials.get("__executionId__");
        if (raw == null) return null;
        String s = raw.toString();
        return s.isBlank() ? null : s;
    }

    // ========== Conversion Helpers ==========

    /**
     * Build a composite {@link PreIterationGuard} for this run.
     * Delegates to {@link GuardChainFactory} which handles tenant + agent budget guards.
     */
    private PreIterationGuard buildGuardChain(AgentExecutionRequestDto request) {
        return guardChainFactory.forAgentWithFallback(
            request.tenantId(), request.agentEntityId(),
            request.maxCreditBudget(), request.creditsConsumedSoFar(),
            request.provider(), request.model());
    }

    private AgentLoopContext convertToContext(AgentExecutionRequestDto request, PreIterationGuard guard,
                                              String executionId) {
        // Convert tool DTOs (List<Map>) to ToolDefinition objects
        List<ToolDefinition> tools = null;
        if (request.tools() != null && !request.tools().isEmpty()) {
            tools = request.tools().stream()
                .map(this::mapToToolDefinition)
                .toList();
        } else if (Boolean.TRUE.equals(request.autoDiscoverTools())) {
            // Orchestrator sends tools=null with autoDiscoverTools=true for workflow agents.
            // Scope the cached core tools to the agent's enabled modules so the workflow
            // path matches the chat path (AgentContextBuilder → CoreToolsProvider): a
            // restricted agent (toolsConfig mode=none/custom) no longer pays for every core
            // tool SCHEMA on every iteration. The module set is resolved upstream by
            // AgentModuleResolver and travels on the DTO; we rebuild the filtered core tool
            // names via the SAME canonical DefaultSystemPrompts.build(modules) the chat path
            // uses, then ask the cache for only those.
            //
            // enabledModules == null ⇒ the producer decided nothing, NOT "everything": fall
            // back to AgentModuleResolver.NO_CONFIG_MODULES (= resolveEnabledModules(null)),
            // which is the platform's own definition of unrestricted and deliberately leaves
            // out the credit-spending opt-in (generation). The previous
            // unfiltered coreToolsCache.getCoreTools() fallback handed the generation tool to
            // any agent row with no toolsConfig, so it could spend the customer's credits with
            // no grant set anywhere.
            Set<String> modules = request.enabledModules() != null
                ? new LinkedHashSet<>(request.enabledModules())
                : new LinkedHashSet<>(AgentModuleResolver.NO_CONFIG_MODULES);
            Set<String> coreToolNames = DefaultSystemPrompts.build(modules, false).coreToolNames();
            tools = coreToolsCache.getCoreTools(coreToolNames);
            // CE→cloud web-search relay: hide web_search from workflow agents whose
            // tenant cannot use it (local engine disabled AND not cloud-linked).
            if (webSearchRelayGate != null) {
                tools = webSearchRelayGate.filterExposedTools(tools, request.tenantId());
            }
            if (tools.isEmpty()) {
                log.warn("CoreToolsCache returned empty tools for remote agent execution - cache may not be initialized yet");
            } else {
                log.info("Injected {} core tools from cache for remote agent execution", tools.size());
            }
        }

        // Convert conversation history DTOs to Message objects
        List<Message> history = null;
        if (request.conversationHistory() != null) {
            history = request.conversationHistory().stream()
                .map(this::mapToMessage)
                .toList();
        }

        // Convert attachment DTOs (List<Map> with base64 data) to MessageAttachment objects
        List<MessageAttachment> messageAttachments = null;
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            messageAttachments = request.attachments().stream()
                .map(this::mapToMessageAttachment)
                .filter(java.util.Objects::nonNull)
                .toList();
        }

        Map<String, Object> credentials = request.credentials() != null
            ? new HashMap<>(request.credentials())
            : new HashMap<>();
        if (executionId != null && !executionId.isBlank()) {
            credentials.put("__executionId__", executionId);
        }
        // The caller's own capabilities travel on the request as a typed field, and every
        // permission check downstream reads them out of CREDENTIALS instead. Left unmirrored,
        // a workflow agent node reaches those checks with an empty answer, and both
        // AgentModuleResolver.callerMayUse and ToolAccessControl read silence as "allowed",
        // so the gap is an open gate rather than a closed one.
        // An EMPTY list is included on purpose. It is the most restricted caller there is
        // (mode=off, zero tools), and skipping it would leave the credential absent, which
        // callerMayUse reads as ALLOWED: the one caller that may do nothing would be the one
        // permitted everything.
        if (request.enabledModules() != null
                && credentials.get(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY) == null) {
            credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY,
                    List.copyOf(request.enabledModules()));
        }
        // Whose key this execution runs on, resolved ONCE here for the EXECUTION provider
        // (request already carries the execution identity when a link redirected it). Also
        // stamped into the credentials, the channel a parent already uses to hand
        // __executionId__ / __taskId__ to the sub-agents it spawns: a child inherits the
        // pin and never re-resolves.
        com.apimarketplace.agent.domain.KeyRoute keyRoute = keyRouteResolver != null
            ? keyRouteResolver.resolve(request.tenantId(), request.provider())
            : null;
        if (keyRoute != null) {
            com.apimarketplace.agent.domain.KeyRoute.stamp(credentials, keyRoute, request.provider());
        }

        return AgentLoopContext.builder()
            .provider(request.provider())
            .model(request.model())
            .systemPrompt(request.systemPrompt())
            .userPrompt(request.prompt())
            .currentMessageAttachments(messageAttachments)
            .conversationHistory(history)
            .tools(tools)
            .autoDiscoverTools(request.autoDiscoverTools())
            .maxTools(request.maxTools())
            .maxIterations(request.maxIterations())
            .executionTimeout(request.executionTimeout())
            // Clamp to the model's real output ceiling so a high platform default never
            // 400s against a low-cap model (DeepSeek-chat = 8192); unknown ⇒ 8192 floor.
            .maxTokens(com.apimarketplace.agent.config.MaxTokensClamp.clamp(
                    request.maxTokens(),
                    modelCatalogService.resolveMaxOutputTokens(request.provider(), request.model())))
            // Observability only: lets the loop report context use as a share of what this
            // model can hold. Null (model absent from the catalog) is fine - the
            // loop then reports size without claiming a severity.
            .contextWindow(modelCatalogService.resolveContextWindow(request.provider(), request.model()))
            .temperature(request.temperature())
            .tenantId(request.tenantId())
            .keyRoute(keyRoute)
            .agentId(request.agentEntityId())
            .runId(request.runId())
            .executionId(executionId)
            .nodeId(request.nodeId())
            .credentials(credentials)
            .variables(request.variables())
            .preIterationGuard(guard)
            .loopIdenticalStop(request.loopIdenticalStop())
            .loopConsecutiveStop(request.loopConsecutiveStop())
            .reasoningEffort(request.reasoningEffort())
            .purpose(CallPurpose.MAIN)
            .build();
    }

    @SuppressWarnings("unchecked")
    private ToolDefinition mapToToolDefinition(Map<String, Object> map) {
        return ToolDefinition.builder()
            .id((String) map.get("id"))
            .name((String) map.get("name"))
            .description((String) map.get("description"))
            .apiSlug((String) map.get("apiSlug"))
            .toolSlug((String) map.get("toolSlug"))
            .parameters(map.get("parameters") != null
                ? objectMapper.convertValue(map.get("parameters"), new TypeReference<>() {})
                : null)
            .requiredParameters(map.get("requiredParameters") != null
                ? (List<String>) map.get("requiredParameters")
                : null)
            .metadata(map.get("metadata") != null
                ? (Map<String, Object>) map.get("metadata")
                : null)
            .timeoutMs(map.get("timeoutMs") != null
                ? ((Number) map.get("timeoutMs")).longValue()
                : null)
            .build();
    }

    private MessageAttachment mapToMessageAttachment(Map<String, Object> map) {
        try {
            String typeStr = (String) map.get("type");
            String mimeType = (String) map.get("mimeType");
            String fileName = (String) map.get("fileName");
            String base64Data = (String) map.get("data");
            String extractedText = (String) map.get("extractedText");
            @SuppressWarnings("unchecked")
            Map<String, Object> fileRef = map.get("fileRef") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : null;

            AttachmentType type = typeStr != null
                ? AttachmentType.valueOf(typeStr.toUpperCase())
                : AttachmentType.OTHER;

            byte[] data = base64Data != null
                ? Base64.getDecoder().decode(base64Data)
                : null;

            return MessageAttachment.builder()
                .type(type)
                .mimeType(mimeType)
                .fileName(fileName)
                .data(data)
                .extractedText(extractedText)
                .fileRef(fileRef)
                .build();
        } catch (Exception e) {
            log.warn("Failed to convert attachment: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Message mapToMessage(Map<String, Object> map) {
        String roleStr = (String) map.get("role");
        Message.Role role = roleStr != null ? Message.Role.valueOf(roleStr.toUpperCase()) : Message.Role.USER;

        return Message.builder()
            .role(role)
            .content((String) map.get("content"))
            .toolCallId((String) map.get("toolCallId"))
            .toolName((String) map.get("toolName"))
            .build();
    }

    private AgentExecutionResponseDto convertToResponseDto(AgentLoopResult result, long duration,
                                                             ConversationRedisStreamingCallback.ConversationCallback conversationCallback,
                                                             com.apimarketplace.agent.domain.KeyRoute keyRoute) {
        // Convert tool results to Maps
        List<Map<String, Object>> toolResultMaps = result.toolResults() != null
            ? result.toolResults().stream()
                .map(tr -> objectMapper.convertValue(tr, new TypeReference<Map<String, Object>>() {}))
                .toList()
            : List.of();

        // Convert conversation history to Maps
        List<Map<String, Object>> historyMaps = result.conversationHistory() != null
            ? result.conversationHistory().stream()
                .map(msg -> objectMapper.convertValue(msg, new TypeReference<Map<String, Object>>() {}))
                .toList()
            : List.of();

        // Convert usage to Map
        Map<String, Object> usageMap = result.usage() != null
            ? objectMapper.convertValue(result.usage(), new TypeReference<>() {})
            : Map.of();

        // Convert per-iteration usage to Maps
        List<Map<String, Object>> usagePerIteration = result.usagePerIteration() != null
            ? result.usagePerIteration().stream()
                .map(u -> objectMapper.convertValue(u, new TypeReference<Map<String, Object>>() {}))
                .toList()
            : List.of();

        String content = result.response() != null ? result.response().content() : null;
        String finalResponse = content;

        // Extract conversation callback data for response enrichment
        List<Map<String, Object>> thinkingSectionsList = List.of();
        List<Map<String, Object>> orderedEntriesList = List.of();
        long reasoningDurationMs = duration;
        if (conversationCallback != null) {
            thinkingSectionsList = conversationCallback.getThinkingSections();
            orderedEntriesList = conversationCallback.getOrderedEntries();
            reasoningDurationMs = System.currentTimeMillis() - conversationCallback.getStreamStartTime();
        }

        // Enrich metrics with conversation-specific data
        Map<String, Object> enrichedMetrics = new HashMap<>(result.metrics() != null ? result.metrics() : Map.of());
        if (conversationCallback != null) {
            enrichedMetrics.put("reasoningDurationMs", reasoningDurationMs);
        }
        // Whose key this execution ran on, for the caller's observability report (which is
        // what reaches the debit): the record has no dedicated field and 120+ positional
        // constructions, so it rides on the metrics map under a stable key.
        if (keyRoute != null) {
            enrichedMetrics.put(KEY_ROUTE_METRIC, keyRoute.name());
        }

        return new AgentExecutionResponseDto(
            result.success(),
            finalResponse,
            content,
            toolResultMaps,
            result.iterations(),
            usageMap,
            result.error(),
            duration,
            result.provider(),
            result.model(),
            historyMaps,
            result.stopReason() != null ? result.stopReason().name() : null,
            enrichedMetrics,
            usagePerIteration,
            result.iterationDurations() != null ? result.iterationDurations() : List.of(),
            result.finishReasonsPerIteration() != null ? result.finishReasonsPerIteration() : List.of(),
            thinkingSectionsList,
            orderedEntriesList,
            null  // budgetScope - local execution; scope is set via observability path
        );
    }

}
