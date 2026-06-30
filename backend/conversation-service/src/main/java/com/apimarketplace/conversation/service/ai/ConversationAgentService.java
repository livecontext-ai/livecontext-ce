package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage;
import com.apimarketplace.agent.client.queue.AgentQueueProducer;
import com.apimarketplace.agent.client.queue.RedisResultWaiter;
import com.apimarketplace.agent.domain.MessageAttachment;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.service.approval.ToolAuthorizationApprovalService;
import com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder;
import com.apimarketplace.conversation.service.ai.callback.ToolCallClassifier;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Agent service for conversation mode.
 * Dispatches LLM execution to agent-service via AgentClient (remote execution).
 * Streaming events reach the frontend via Redis (ConversationRedisStreamingCallback in agent-service).
 * DB persistence happens after the HTTP response returns.
 *
 * Architecture (SOLID):
 * - This class: Orchestration only (Single Responsibility)
 * - AgentContextBuilder: Builds execution context
 * - AgentObservabilityClient: Records execution metrics to agent-service
 */
@Slf4j
@Service
public class ConversationAgentService {

    private final AgentContextBuilder contextBuilder;
    private final AgentObservabilityClient observabilityClient;
    private final AgentConfigProvider agentConfigProvider;
    private final CreditConsumptionClient creditClient;
    private final MessageService messageService;
    private final PendingActionService pendingActionService;
    private final ToolResultService toolResultService;
    private final ObjectMapper objectMapper;
    private final AgentClient agentClient;
    private final StreamStateService stateService;
    private final EventBus eventBus;
    private final HelpSeenRegistry helpSeenRegistry;
    private final MessageRepository messageRepository;
    private final String selfUrl;

    @Value("${conversation.bridge.enabled:false}")
    private boolean bridgeEnabled;

    @Autowired(required = false)
    private BridgeClient bridgeClient;

    /**
     * Optional queue producer - wired only when {@code scaling.agent.queue.enabled=true}.
     * When present, cloud (non-bridge) agent executions are routed through Redis to the
     * shared {@code AgentQueueWorkerService} pool in agent-service rather than via
     * synchronous HTTP. Bridge executions stay on the in-process {@link BridgeClient}
     * path until the bridge dispatch is folded into the queue (deferred to a later PR).
     */
    @Autowired(required = false)
    private AgentQueueProducer queueProducer;

    /**
     * Optional sync-await waiter - wired alongside {@link #queueProducer}. Subscribes
     * to {@code agent:result:channel:{correlationId}} and returns the deserialized
     * response on the same thread, preserving the existing {@code executeRemote}
     * req/resp contract (front-end keeps receiving SSE chunks via the conversation
     * streaming callback that publishes from agent-service in parallel).
     */
    @Autowired(required = false)
    private RedisResultWaiter resultWaiter;

    /**
     * Maximum time to wait for an agent execution result on the queue path. Matches the
     * 35-minute read timeout {@code AgentClient} uses for synchronous HTTP execution
     * (see {@code AgentClient.createExecutionRestTemplate} - 65 min connection budget
     * with a 35-min effective per-execution ceiling). If a chat session legitimately
     * runs longer, the user sees the same timeout as before.
     */
    private static final Duration QUEUE_AWAIT_TIMEOUT = Duration.ofMinutes(35);

    /** Redis Pub/Sub WebSocket fan-out channel - the gateway (cloud) and MonolithWsHandler (CE) both relay it to the browser. */
    private static final String WS_CONVERSATION_PREFIX = "ws:conversation:";

    /** Best-effort timeout for the reactive stream-state writes on the bridge sync path. */
    private static final Duration STREAM_STATE_WRITE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Sync-execution sources whose run is attached to a real conversation a user can open,
     * so the run should stream live into that conversation (register a reconnectable stream +
     * emit start/done) exactly like an interactive chat. WIDGET is intentionally excluded -
     * it has its own response surface and is not a browsable conversation page.
     */
    private static final java.util.Set<String> CONVERSATION_STREAMING_SYNC_SOURCES =
            java.util.Set.of("SCHEDULE", "WEBHOOK", "TASK", "TASK_REVIEW");

    /**
     * CLI-bridge access enforcer. Required on the conversation bridge path because
     * {@link BridgeClient#executeViaBridge} talks directly to the Node.js bridge
     * server (port 8093) and bypasses agent-service entirely - so the shared-
     * agent-lib {@code BridgeAccessGuard} call site never fires for the primary
     * chat flow. Always present (local component); fails-CLOSED when auth-service
     * is unreachable, no-ops for non-bridge providers.
     */
    @Autowired
    private BridgeAccessEnforcer bridgeAccessEnforcer;

    /**
     * Resolves user-authorized sensitive tool actions for the conversation. Read
     * when building the execution request so {@code __approvedToolActions__} can be
     * threaded into agent-service credentials (consumed by {@code ToolAuthorizationGuard}).
     */
    @Autowired
    private ToolAuthorizationApprovalService toolAuthorizationApprovalService;

    @Autowired
    public ConversationAgentService(
            AgentContextBuilder contextBuilder,
            AgentObservabilityClient observabilityClient,
            AgentConfigProvider agentConfigProvider,
            CreditConsumptionClient creditClient,
            MessageService messageService,
            PendingActionService pendingActionService,
            ToolResultService toolResultService,
            ObjectMapper objectMapper,
            AgentClient agentClient,
            StreamStateService stateService,
            EventBus eventBus,
            HelpSeenRegistry helpSeenRegistry,
            MessageRepository messageRepository,
            @Value("${conversation.service.url:http://localhost:8087}") String selfUrl) {
        this.contextBuilder = contextBuilder;
        this.observabilityClient = observabilityClient;
        this.agentConfigProvider = agentConfigProvider;
        this.creditClient = creditClient;
        this.messageService = messageService;
        this.pendingActionService = pendingActionService;
        this.toolResultService = toolResultService;
        this.objectMapper = objectMapper;
        this.agentClient = agentClient;
        this.stateService = stateService;
        this.eventBus = eventBus;
        this.helpSeenRegistry = helpSeenRegistry;
        this.messageRepository = messageRepository;
        this.selfUrl = selfUrl;
    }

    /**
     * Execute an agent conversation with streaming.
     *
     * <p>Bridge-vs-remote routing reads {@code dto.provider() / dto.model()}
     * AFTER {@link AgentContextBuilder#build} applies any agent-config override
     * - never {@code request.getProvider()} directly. Same order as
     * {@link #executeSync}.
     *
     * @param request The chat request from the user
     * @param streamOutput The streaming output for real-time updates
     * @param conversationId The conversation ID
     */
    public void executeStreaming(ChatRequest request, StreamingOutput streamOutput, String conversationId) {
        String streamId = streamOutput.getCurrentStreamId();
        // Mint the stable executionId at dispatch. Same UUID flows into:
        //   1. AgentExecutionRequestDto.executionId - agent-service uses as agent_executions.id PK
        //   2. MCP credentials __executionId__ - AgentTaskService.claimTask writes claim log keyed by it
        //   3. WS payload executionId - frontend correlates fleet events with the same id
        // Replaces the previous 3 independent UUID.randomUUID() mints (one per concern).
        String executionId = UUID.randomUUID().toString();
        try {
            Double creditBudget = fetchCreditBudget(request.getUserId());
            AgentLoopContext context = contextBuilder.build(request, conversationId, streamId, executionId);
            AgentExecutionRequestDto dto = buildExecutionRequest(context, streamId, conversationId, creditBudget,
                request.getTaskId(), request.getSource(), executionId);

            boolean useBridge = bridgeEnabled && bridgeClient != null && isBridgeProvider(dto.provider(), dto.model());
            log.info("Starting agent streaming: conv={}, requestProvider={}, requestModel={}, resolvedProvider={}, resolvedModel={}, useBridge={}",
                conversationId, request.getProvider(), request.getModel(), dto.provider(), dto.model(), useBridge);
            if (useBridge) {
                executeViaBridge(request, context, dto, streamOutput, conversationId);
            } else {
                executeRemote(request, context, dto, streamOutput, conversationId);
            }
        } catch (Exception e) {
            setCancelKeyQuietly(streamId);
            handleExecutionError(e, streamOutput, conversationId);
        }
    }

    /**
     * Check if the request should use the bridge (CLI-based providers).
     * Routes to bridge when provider is a CLI-based agent:
     * - claude-code: Claude Code CLI (Anthropic commercial terms)
     * - codex: OpenAI Codex CLI (Apache 2.0)
     * - gemini-cli: Google Gemini CLI (Apache 2.0)
     * - mistral-vibe: Mistral Vibe CLI (Apache 2.0)
     */
    private boolean isBridgeProvider(String provider, String model) {
        if (provider == null || provider.isBlank()) return false;
        String lower = provider.toLowerCase();
        return "claude-code".equals(lower)
            || "codex".equals(lower)
            || "gemini-cli".equals(lower)
            || "mistral-vibe".equals(lower);
    }

    // ========== Synchronous Execution (for webhook / schedule) ==========

    /**
     * Execute an agent conversation synchronously - no streaming, returns the result directly.
     * Same pipeline as executeRemote: context building, agent execution, persistence, observability.
     * Used by webhook and schedule via /api/internal/chat/sync.
     *
     * @return map with {success, content, error, conversationId}
     */
    public Map<String, Object> executeSync(ChatRequest request, String conversationId) {
        // Hoisted out of the try so the catch(Exception) branch below can prefer
        // dto.provider()/dto.model() (RESOLVED post-agent-override) over the raw
        // request.getProvider()/getModel() (chat header pre-override) when the
        // exception fires AFTER buildExecutionRequest. Avoids the slug-mismatch
        // class where an agent override (e.g. claude-code→deepseek) would
        // persist the wrong model on the failure row and bucket it into the
        // unused-model aggregate on the Fleet chip.
        AgentExecutionRequestDto resolvedDto = null;
        // Mint at the OUTER boundary so failure paths (catch + 402 + null-response) can
        // surface the same executionId in fleet events. Single source of truth across
        // success and failure branches.
        String executionId = UUID.randomUUID().toString();

        // Conversation streaming for sync (schedule/webhook/task) runs. Eligible sources get a
        // REAL stream id so the run can be registered as a reconnectable stream and stream live
        // into the conversation like an interactive chat; other sources keep the legacy synthetic
        // id. On the bridge transport we emulate the streaming callback below (the bridge bypasses
        // agent-service); the remote transport already streams via ConversationRedisStreamingCallback.
        boolean eligibleForConvStream = conversationId != null
                && isConversationStreamingSource(request.getSource());
        String streamId = eligibleForConvStream
                ? UUID.randomUUID().toString()
                : ("sync-" + conversationId);
        // Non-null only once we actively emulate the conversation stream (bridge sync path), so
        // every terminal path (success / failure / null-response / exception) can finalize it.
        String emulatedStreamId = null;

        try {
            Double creditBudget = fetchCreditBudget(request.getUserId());
            AgentLoopContext context = contextBuilder.build(request, conversationId, streamId, executionId);

            AgentExecutionRequestDto dto = buildExecutionRequest(context, streamId, conversationId, creditBudget,
                request.getTaskId(), request.getSource(), executionId);
            resolvedDto = dto;

            boolean useBridge = bridgeEnabled && bridgeClient != null && isBridgeProvider(dto.provider(), dto.model());
            log.info("Sync agent execution: conversationId={}, provider={}, model={}, source={}, useBridge={}",
                    conversationId, dto.provider(), dto.model(), request.getSource(), useBridge);

            // Enforce CLI subscription on the RESOLVED provider - must run before
            // dispatch but after the agent override is applied. A webhook/schedule
            // delegating to an attached agent whose configured provider differs
            // from the trigger's request provider would otherwise either skip the
            // gate (request says non-bridge → no-op) or gate the wrong slug.
            if (useBridge) {
                bridgeAccessEnforcer.enforce(request.getUserId(), request.getUserRoles(), dto.provider());
            }

            // Bridge sync runs bypass agent-service, so nothing registers a reconnectable stream
            // or emits start/done into the conversation. Emulate that here so the run streams live
            // and a refreshed conversation page reconnects exactly like an interactive chat.
            // (Remote sync streams via ConversationRedisStreamingCallback in agent-service.)
            if (eligibleForConvStream && useBridge) {
                emulatedStreamId = streamId;
                registerConversationStreamQuietly(streamId, conversationId, dto.model(), dto.provider());
                publishConversationStreamStarted(streamId, conversationId, dto.model());
            }

            // Publish fleet activity for sync bridge dispatches (schedule / webhook). Without
            // this the task board never knows the agent is running between claim and submit -
            // useAgentActivityStream gets nothing and the task card cannot shimmer. The remote
            // path publishes via AgentActivityPublisher in agent-service, but the bridge sync
            // path bypasses agent-service entirely so it has to emit here. taskId comes from
            // the incoming ChatRequest - null for schedule fires that pick tasks via MCP after
            // start (the agent-service claimTask hook fires a follow-up event in that case).
            String agentEntityId = dto.agentEntityId();
            // Reuse the outer executionId (minted at method entry, line 223) so the same
            // UUID flows through the WS publishes, the agent_executions.id, and the MCP
            // credentials __executionId__.
            String taskId = request.getTaskId();
            long syncStartMs = System.currentTimeMillis();
            if (useBridge && agentEntityId != null) {
                publishFleetEvent(agentEntityId, executionId, "execution_started",
                        dto.model(), dto.source() != null ? dto.source() : "CONVERSATION", taskId);
            }

            AgentExecutionResponseDto response;
            try {
                response = useBridge
                        ? bridgeClient.executeViaBridge(dto)
                        : dispatchAgentExecution(dto, request.getUserRoles());
            } catch (RuntimeException ex) {
                // Inner try only exists to emit a paired execution_completed(FAILED)
                // before re-throwing - the outer catch (line ~280) converts to the
                // standard {success=false, error, conversationId} map the caller
                // expects, so the re-throw is correct: the inner is observability
                // bookkeeping, the outer is the contract.
                if (useBridge && agentEntityId != null) {
                    publishFleetExecutionCompleted(agentEntityId, executionId, "FAILED",
                            0, 0, System.currentTimeMillis() - syncStartMs, taskId);
                }
                throw ex;
            }

            if (response == null) {
                log.error("Agent-service returned null response for sync execution: {}", conversationId);
                if (useBridge && agentEntityId != null) {
                    publishFleetExecutionCompleted(agentEntityId, executionId, "FAILED",
                            0, 0, System.currentTimeMillis() - syncStartMs, taskId);
                }
                // Persist a typed assistant error message so the conv reflects the
                // failure. Without this the schedule/webhook caller's only signal is
                // the {success=false} map - the conversation looks empty (or shows
                // only the user prompt) and the user has no way to know the agent
                // execution failed. The streaming path already does this via
                // handleExecutionError; mirror it here for the sync path.
                persistSyncErrorMessage(conversationId, "Agent execution failed: no response from agent transport");
                // Surface in Agent Performance / Agent Fleet too - without a FAILED
                // execution row the dashboards stay empty for this attempt. Thread the
                // userPrompt + a synthetic assistant error line so the execution detail
                // view shows what the agent was supposed to do and why nothing came back.
                observabilityClient.recordFailureAsync(request.getUserId(), request.getOrgId(),
                        agentEntityId, request.getSource(), conversationId,
                        "FAILED", "Agent execution failed: no response from agent transport",
                        context.userPrompt(),
                        "[Error] Agent execution failed: no response from agent transport",
                        dto.provider(), dto.model());
                finalizeBridgeSyncStream(emulatedStreamId, conversationId, dto.model(), false, null,
                        "Agent execution failed: no response from agent transport");
                return Map.of("success", false, "error", "Agent execution failed: no response", "conversationId", conversationId);
            }

            log.info("Sync agent execution completed: success={}, iterations={}, duration={}ms",
                    response.success(), response.iterations(), response.durationMs());

            if (useBridge && agentEntityId != null) {
                publishFleetExecutionCompleted(agentEntityId, executionId,
                        response.success() ? "COMPLETED" : "FAILED",
                        0, response.iterations(),
                        response.durationMs() > 0 ? response.durationMs() : System.currentTimeMillis() - syncStartMs,
                        taskId);
            }

            // Sync path (webhook/schedule) has no SSE subscriber - pass null streamId
            // so the compaction orchestrator skips its best-effort event publish.
            persistRemoteResults(request, conversationId, response, null, executionId);
            recordObservability(request, context, response, conversationId);

            String content = response.content() != null ? response.content() : "";
            if (response.success()) {
                finalizeBridgeSyncStream(emulatedStreamId, conversationId, dto.model(), true, content, null);
                return Map.of("success", true, "content", content, "conversationId", conversationId);
            } else {
                String error = response.error() != null ? response.error() : "Agent execution failed";
                // persistRemoteResults above writes a row when ANY of (content, toolResults,
                // thinkingSections) is non-empty. Only persist a typed [Error] message when
                // persistRemoteResults wrote nothing - otherwise we'd land two assistant
                // rows for the same failure (one with empty content + tool calls/thinking
                // from persistRemoteResults, one with [Error] from here).
                boolean persistRemoteResultsWroteNothing = content.isEmpty()
                        && (response.toolResults() == null || response.toolResults().isEmpty())
                        && (response.thinkingSections() == null || response.thinkingSections().isEmpty());
                if (persistRemoteResultsWroteNothing) {
                    persistSyncErrorMessage(conversationId, error);
                }
                finalizeBridgeSyncStream(emulatedStreamId, conversationId, dto.model(), false, content, error);
                return Map.of("success", false, "error", error, "content", content, "conversationId", conversationId);
            }

        } catch (Exception e) {
            log.error("Sync agent execution error for {}: {}", conversationId, e.getMessage(), e);
            persistSyncErrorMessage(conversationId, e.getMessage());
            // Best-effort failure row so the attempt is visible in Agent Performance.
            // We may not have a resolved agentEntityId here (exception thrown before
            // contextBuilder.build), so fall back to request.getAgentId().
            String agentIdForRecord = request.getAgentId();
            if (agentIdForRecord != null && !agentIdForRecord.isBlank()) {
                // Prefer the RESOLVED dto values (post-agent-override) over the raw
                // request fields when the exception fires after buildExecutionRequest.
                // A webhook calling agent A configured for claude-opus-4-7 with a chat
                // header pointing at gpt-4o would otherwise persist 'gpt-4o' on the
                // FAILED row and bucket it into the wrong model aggregate on the Fleet
                // chip. Falls back to raw request fields only when dto wasn't built
                // yet (pre-build exception path).
                String providerForRecord = resolvedDto != null ? resolvedDto.provider() : request.getProvider();
                String modelForRecord = resolvedDto != null ? resolvedDto.model() : request.getModel();
                observabilityClient.recordFailureAsync(request.getUserId(), request.getOrgId(),
                        agentIdForRecord, request.getSource(), conversationId,
                        "FAILED", e.getMessage(),
                        request.getMessage(),
                        "[Error] " + (e.getMessage() != null ? e.getMessage() : "Agent execution failed"),
                        providerForRecord, modelForRecord);
            }
            if (emulatedStreamId != null) {
                String modelForStream = resolvedDto != null ? resolvedDto.model() : request.getModel();
                finalizeBridgeSyncStream(emulatedStreamId, conversationId, modelForStream, false, null, e.getMessage());
            }
            return Map.of("success", false, "error", e.getMessage(), "conversationId", conversationId);
        }
    }

    /**
     * @return true when a sync execution {@code source} should stream live into its conversation
     * (register a reconnectable stream + emit start/done), matching the interactive chat
     * experience. See {@link #CONVERSATION_STREAMING_SYNC_SOURCES} (WIDGET is excluded).
     */
    private boolean isConversationStreamingSource(String source) {
        return source != null
                && CONVERSATION_STREAMING_SYNC_SOURCES.contains(source.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Register a reconnectable external stream so the conversation snapshot endpoint and the
     * frontend's open/refresh reconnection ({@code getStreamStatus} → {@code getStreamState})
     * resolve this run. Mirrors the remote path's ConversationRedisStreamingCallback registration
     * and the CE stub's {@code /api/internal/streams/register}. Best-effort: a Redis hiccup must
     * not fail the agent run (live events still flow; only reconnect-replay would be lost).
     */
    private void registerConversationStreamQuietly(String streamId, String conversationId,
                                                   String model, String provider) {
        if (streamId == null || conversationId == null) return;
        try {
            stateService.registerExternalStream(streamId, conversationId, model, provider)
                    .block(STREAM_STATE_WRITE_TIMEOUT);
        } catch (Exception e) {
            log.warn("[SYNC_STREAM] Failed to register stream (best-effort) streamId={} conv={}: {}",
                    streamId, conversationId, e.getMessage());
        }
    }

    /**
     * Emit a {@code stream_started}-shaped event on {@code ws:conversation:{conversationId}} so an
     * actively-subscribed conversation page flips into streaming mode. The frontend detects it via
     * the {@code (model, conversationId)} field pair - same shape as the remote path's stream_started.
     */
    private void publishConversationStreamStarted(String streamId, String conversationId, String model) {
        if (streamId == null || conversationId == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("conversationId", conversationId);
            event.put("model", model != null ? model : "unknown");
            event.put("timestamp", Instant.now().toString());
            eventBus.publish(WS_CONVERSATION_PREFIX + conversationId, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.debug("[SYNC_STREAM] Failed to publish stream_started (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Finalize the emulated bridge sync stream: emit the terminal event the frontend needs to close
     * the streaming bubble ({@code done} on success / {@code error} on failure) and advance the
     * persisted stream state so a post-completion reconnect replays a terminal event rather than a
     * stuck "streaming". No-op when {@code streamId} is null (this run did not emulate a stream).
     *
     * <p>On failure we ALWAYS emit the {@code error} event because the bridge does not reliably emit
     * a terminal one: a soft failure ({@code success=false} body) or an unreachable bridge
     * ({@code BridgeClient.executeViaBridge} returns {@code null}) leaves the live bubble stuck
     * otherwise. When the bridge DID already publish its own live {@code error} (an in-run adapter
     * failure), this re-emits a second one - harmless because the terminal state is idempotent, and
     * strictly preferable to risking a stuck stream by trying to guess whether the bridge emitted one.
     */
    private void finalizeBridgeSyncStream(String streamId, String conversationId, String model,
                                          boolean success, String fullContent, String error) {
        if (streamId == null) return;
        if (success) {
            publishConversationStreamDone(streamId, conversationId, model, fullContent);
            updateConversationStreamStateQuietly(streamId, true, null);
        } else {
            publishConversationStreamError(streamId, conversationId, error);
            updateConversationStreamStateQuietly(streamId, false, error);
        }
    }

    /** Emit the {@code done}/completed event (frontend detects it via the {@code (fullContent, totalTokens)} pair). */
    private void publishConversationStreamDone(String streamId, String conversationId,
                                               String model, String fullContent) {
        if (streamId == null || conversationId == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("conversationId", conversationId);
            event.put("model", model != null ? model : "unknown");
            event.put("fullContent", fullContent != null ? fullContent : "");
            event.put("totalTokens", 0);
            event.put("timestamp", Instant.now().toString());
            eventBus.publish(WS_CONVERSATION_PREFIX + conversationId, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.debug("[SYNC_STREAM] Failed to publish done (non-critical): {}", e.getMessage());
        }
    }

    /** Emit a stream {@code error} event (frontend detects it via the {@code (error, errorCode)} pair). */
    private void publishConversationStreamError(String streamId, String conversationId, String error) {
        if (streamId == null || conversationId == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("conversationId", conversationId);
            event.put("error", error != null ? error : "Agent execution failed");
            event.put("errorCode", "STREAM_ERROR");
            event.put("retryable", true);
            event.put("timestamp", Instant.now().toString());
            eventBus.publish(WS_CONVERSATION_PREFIX + conversationId, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.debug("[SYNC_STREAM] Failed to publish error (non-critical): {}", e.getMessage());
        }
    }

    /** Advance the persisted stream state (COMPLETED / ERROR). Best-effort - see {@link #registerConversationStreamQuietly}. */
    private void updateConversationStreamStateQuietly(String streamId, boolean success, String error) {
        if (streamId == null) return;
        try {
            if (success) {
                stateService.complete(streamId).block(STREAM_STATE_WRITE_TIMEOUT);
            } else {
                stateService.error(streamId, error).block(STREAM_STATE_WRITE_TIMEOUT);
            }
        } catch (Exception e) {
            log.debug("[SYNC_STREAM] Failed to finalize stream state (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Persist a typed assistant error message in the sync conversation. Best-effort
     * - failure to persist must not mask the original error from the caller (the
     * schedule daemon already advanced next_execution_at and just wants the verdict).
     * Mirrors the streaming path's handleExecutionError behavior so a sync (webhook
     * / schedule) failure leaves the same audit trail in the conversation as a
     * streaming UI failure would.
     */
    private void persistSyncErrorMessage(String conversationId, String errorMessage) {
        if (conversationId == null) return;
        try {
            MessageDto errMsg = new MessageDto();
            errMsg.setConversationId(conversationId);
            errMsg.setRole("assistant");
            errMsg.setContent("[Error] " + (errorMessage != null ? errorMessage : "Agent execution failed"));
            errMsg.setTimestamp(Instant.now().toString());
            messageService.addMessage(conversationId, errMsg);
        } catch (Exception persistErr) {
            log.warn("Failed to persist sync error message for {}: {}", conversationId, persistErr.getMessage());
        }
    }

    // ========== Remote Execution ==========

    /**
     * Dispatch agent execution to agent-service. When {@link #queueProducer} and
     * {@link #resultWaiter} are wired ({@code scaling.agent.queue.enabled=true}),
     * the request goes through the shared Redis queue consumed by
     * {@code AgentQueueWorkerService}; otherwise it falls back to synchronous HTTP
     * via {@link AgentClient}.
     *
     * <p>Both paths return the same {@link AgentExecutionResponseDto}, so callers
     * remain transport-agnostic. Streaming chunks reach the front-end through the
     * conversation streaming callback in agent-service (Redis pub/sub) regardless
     * of which transport carried this dispatch.</p>
     */
    private AgentExecutionResponseDto dispatchAgentExecution(AgentExecutionRequestDto dto, String userRoles) {
        if (queueProducer != null && resultWaiter != null) {
            String correlationId = UUID.randomUUID().toString();
            Map<String, Object> payload = objectMapper.convertValue(dto, new TypeReference<>() {});
            AgentExecutionRequestMessage msg = AgentExecutionRequestMessage.create(
                correlationId,
                null, // runId - chat has no workflow run
                null, // nodeId - chat has no workflow node
                dto.tenantId(),
                "agent",
                dto.provider(),
                dto.model(),
                payload,
                userRoles
            );
            log.debug("Routing chat execution via queue: correlationId={}, provider={}, model={}",
                correlationId, dto.provider(), dto.model());
            queueProducer.enqueue(msg);
            return resultWaiter.await(correlationId, AgentExecutionResponseDto.class, QUEUE_AWAIT_TIMEOUT);
        }
        return agentClient.executeAgent(dto);
    }

    /**
     * Dispatch agent execution to agent-service.
     * Streaming events reach the frontend via Redis (ConversationRedisStreamingCallback).
     * DB persistence happens after the response returns.
     *
     * <p>{@code context} and {@code dto} are pre-built by the caller so the
     * bridge-vs-remote decision sees the resolved {@code (provider, model)}.
     */
    private void executeRemote(ChatRequest request, AgentLoopContext context, AgentExecutionRequestDto dto,
                               StreamingOutput streamOutput, String conversationId) {
        String streamId = streamOutput.getCurrentStreamId();
        try {
            log.info("Dispatching conversation agent execution to agent-service: conversationId={}, streamId={}, provider={}, model={}, transport={}",
                conversationId, streamId, dto.provider(), dto.model(),
                (queueProducer != null && resultWaiter != null) ? "queue" : "http");

            AgentExecutionResponseDto response = dispatchAgentExecution(dto, context.userRoles());

            if (response == null) {
                log.error("Agent-service returned null response for conversation: {}", conversationId);
                streamOutput.sendError("Agent execution failed: no response from agent-service");
                return;
            }

            log.info("Remote agent execution completed: success={}, iterations={}, duration={}ms, contentLen={}",
                response.success(), response.iterations(), response.durationMs(),
                response.content() != null ? response.content().length() : 0);

            persistRemoteResults(request, conversationId, response, streamId, dto.executionId());
            persistPendingActionIfNeeded(conversationId, response);
            // Credits are now consumed in agent-service during recordFromChat() to ensure
            // per-execution credit tracking. No double consumption.

            streamOutput.sendDone(
                response.content() != null ? response.content() : "",
                response.model(), response.provider(),
                request.getUserId(), conversationId);

            recordObservability(request, context, response, conversationId);

        } catch (Exception e) {
            // Set cancel key so agent-service stops the running agent loop
            setCancelKeyQuietly(streamId);
            handleExecutionError(e, streamOutput, conversationId);
        }
    }

    /**
     * Dispatch agent execution to the bridge server (Claude Agent SDK + MCP tools).
     * Same post-processing as executeRemote: persist results, credits, observability.
     * Streaming events reach the frontend via Redis (bridge publishes same format).
     *
     * <p>{@code context} and {@code dto} are pre-built; the per-user CLI
     * subscription gate is enforced on {@code dto.provider()} (resolved), not
     * on the chat-header request provider.
     */
    private void executeViaBridge(ChatRequest request, AgentLoopContext context, AgentExecutionRequestDto dto,
                                  StreamingOutput streamOutput, String conversationId) {
        String streamId = streamOutput.getCurrentStreamId();
        try {
            // Gate against the resolved provider - BridgeClient bypasses
            // agent-service so this is the only enforcement point on the chat
            // path. Short-circuits when the provider isn't a bridge slug.
            bridgeAccessEnforcer.enforce(request.getUserId(), request.getUserRoles(), dto.provider());

            log.info("Dispatching conversation agent execution to bridge: conversationId={}, streamId={}, model={}",
                conversationId, streamId, dto.model());

            // Publish fleet activity: execution started (bridge path only - remote path publishes in agent-service).
            // Reuse dto.executionId() (minted at executeStreaming entry) so WS events, MCP credentials
            // and the persisted agent_executions.id all share the same UUID.
            String agentEntityId = dto.agentEntityId();
            String executionId = dto.executionId();
            String taskId = request.getTaskId();
            publishFleetEvent(agentEntityId, executionId, "execution_started",
                dto.model(), dto.source() != null ? dto.source() : "CONVERSATION", taskId);

            AgentExecutionResponseDto response = bridgeClient.executeViaBridge(dto);

            if (response == null) {
                log.error("Bridge returned null response for conversation: {}", conversationId);
                publishFleetExecutionCompleted(agentEntityId, executionId, "FAILED", 0, 0, 0, taskId);
                streamOutput.sendError("Agent execution failed: no response from bridge");
                return;
            }

            log.info("Bridge execution completed: success={}, iterations={}, duration={}ms, contentLen={}",
                response.success(), response.iterations(), response.durationMs(),
                response.content() != null ? response.content().length() : 0);

            // Publish fleet activity: execution completed
            publishFleetExecutionCompleted(agentEntityId, executionId,
                response.success() ? "COMPLETED" : "FAILED",
                0, // tokens not available from bridge response in this path
                response.iterations(),
                response.durationMs(),
                taskId);

            persistRemoteResults(request, conversationId, response, streamId, dto.executionId());
            persistPendingActionIfNeeded(conversationId, response);
            // Bridge path has no ConversationRedisStreamingCallback, so emit
            // the WS event here so the web frontend shows the card in real-time.
            emitPendingApprovalIfPresent(conversationId, response);
            // Credits are now consumed in agent-service during recordFromChat()

            streamOutput.sendDone(
                response.content() != null ? response.content() : "",
                response.model(), response.provider(),
                request.getUserId(), conversationId);

            recordObservability(request, context, response, conversationId);

        } catch (Exception e) {
            setCancelKeyQuietly(streamId);
            handleExecutionError(e, streamOutput, conversationId);
        }
    }

    /**
     * Build AgentExecutionRequestDto from AgentLoopContext for remote dispatch.
     * <p>
     * {@code taskId} is the id of the {@code agent_tasks} row this chat execution is
     * servicing (populated by the task-delegation path via {@code ConversationClient.sendChatSync}).
     * It is injected into credentials as {@code __taskId__} so that agent-service's
     * {@code AgentRemoteExecutionService} can scope fleet activity events (execution_started /
     * tool_call_* / execution_completed) to the specific task. Without it, the task-board
     * shimmer selector - which matches on (agentEntityId, taskId) - never fires because
     * {@code currentTaskId} is null. Null is allowed for non-task conversations (direct
     * chat, standalone webhook/schedule) where per-task scoping is not relevant.
     */
    private AgentExecutionRequestDto buildExecutionRequest(AgentLoopContext context,
                                                            String streamId, String conversationId,
                                                            Double creditBudget, String taskId,
                                                            String source, String executionId) {
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

        // Inject __toolCallbackUrl__ into credentials for conversation-specific tool routing
        Map<String, Object> credentials = new HashMap<>();
        if (context.credentials() != null) {
            credentials.putAll(context.credentials());
        }
        String toolCallbackUrl = selfUrl + "/api/internal/conversation/tools/execute";
        credentials.put("__toolCallbackUrl__", toolCallbackUrl);

        // Inject task id when this chat execution is servicing an agent_tasks row.
        // AgentRemoteExecutionService reads __taskId__ from credentials and stamps it on
        // every fleet activity event (execution_started / tool_call_* / execution_completed)
        // so the task-board shimmer (which matches on agentEntityId + currentTaskId) can fire.
        if (taskId != null && !taskId.isBlank()) {
            credentials.put("__taskId__", taskId);
        }

        // Inject user-authorized sensitive tool actions so the agent-service
        // ToolAuthorizationGuard lets them through without re-prompting. Combines the
        // persisted "always" rules with any single-shot "once" grant (consumed here).
        if (conversationId != null && toolAuthorizationApprovalService != null) {
            List<String> approvedToolActions =
                toolAuthorizationApprovalService.resolveAndConsumeForTurn(conversationId);
            if (!approvedToolActions.isEmpty()) {
                credentials.put("__approvedToolActions__", approvedToolActions);
            }
        }

        // Extract agent budget for DTO (bridge-path enforcement).
        // On the remote (agent-service) path, buildAgentGuard() loads the entity directly
        // so these DTO values are only a fallback. On the bridge path, these are the ONLY
        // source of per-agent budget enforcement.
        Double agentBudget = credentials.get("__creditBudget__") instanceof Number n ? n.doubleValue() : null;
        Double agentConsumed = credentials.get("__creditsConsumed__") instanceof Number n ? n.doubleValue() : null;

        // Convert attachments to serializable maps with base64-encoded data
        List<Map<String, Object>> attachmentMaps = null;
        if (context.hasCurrentMessageAttachments()) {
            attachmentMaps = context.currentMessageAttachments().stream()
                .map(att -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("type", att.type() != null ? att.type().name() : "OTHER");
                    map.put("mimeType", att.mimeType());
                    map.put("fileName", att.fileName());
                    if (att.data() != null) {
                        map.put("data", Base64.getEncoder().encodeToString(att.data()));
                    }
                    if (att.extractedText() != null) {
                        map.put("extractedText", att.extractedText());
                    }
                    return map;
                })
                .toList();
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
            null,   // runId (not applicable for conversation)
            null,   // nodeId (not applicable for conversation)
            context.variables(),
            credentials,
            // Per-agent budget: when the agent entity has a creditBudget configured, pass it
            // so both the remote path (agent-service buildAgentGuard fallback) and the bridge
            // path (JS AgentBudgetGuard) can enforce per-agent budget limits.
            agentBudget,
            streamId,
            null,   // itemIndex
            null,   // loopIteration
            conversationId,
            "conversation",
            null,   // parentConversationId
            null,   // subAgentName
            null,   // subAgentAvatarUrl
            null,   // subAgentId
            null,   // workflowRunId (not applicable for conversation)
            attachmentMaps,
            credentials.get("__agentId__") instanceof String aid ? aid : null,  // agentEntityId - for fleet real-time activity
            // tenantBalance: bridge JS TenantBudgetGuard uses this. Real tenant remaining_credits
            // sourced from CreditConsumptionClient (same as the Java side). This is the ONLY
            // budget enforcement for chat conversations - there is no per-agent quota.
            creditBudget,
            null, // pricingRates - bridge fetches via its own cache
            agentConsumed,  // creditsConsumedSoFar - agent's consumed credits for budget guard
            context.loopIdenticalStop(),
            context.loopConsecutiveStop(),
            executionId,
            normalizeExecutionSource(source),
            // Reasoning effort for CLI/bridge providers - already resolved in
            // AgentContextBuilder (per-conversation override > agent > model default).
            context.reasoningEffort(),
            // enabledModules - the chat DIRECT path already passes the filtered tool list
            // explicitly via toolMaps, but the BRIDGE path ignores toolMaps and builds its own
            // MCP tool set, so it needs the canonical module keys to scope to the agent's
            // toolsConfig.mode. AgentContextBuilder resolved them onto the context; the bridge
            // forwards them via ENABLED_MODULES. null ⇒ unrestricted (no toolsConfig).
            context.enabledModules()
        );
    }

    private String normalizeExecutionSource(String source) {
        if (source == null || source.isBlank()) {
            return "CHAT";
        }
        return source.trim();
    }

    /**
     * Convert ToolDefinition to Map for serialization.
     */
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
     * Persist remote execution results to the conversation database.
     */
    @SuppressWarnings("unchecked")
    private void persistRemoteResults(ChatRequest request, String conversationId,
                                       AgentExecutionResponseDto response, String streamId, String executionId) {
        try {
            String content = response.content() != null ? response.content().trim() : "";
            List<Map<String, Object>> toolResults = response.toolResults();
            List<Map<String, Object>> orderedEntries = response.orderedEntries();
            List<Map<String, Object>> thinkingSections = response.thinkingSections();
            boolean hasContent = !content.isEmpty();
            boolean hasToolResults = toolResults != null && !toolResults.isEmpty();
            boolean hasThinking = thinkingSections != null && !thinkingSections.isEmpty();

            if (!hasContent && !hasToolResults && !hasThinking) {
                log.info("No content or tools to persist for remote conversation: {}", conversationId);
                return;
            }

            // Build a lookup of tool results by toolCallId for O(1) access
            Map<String, Map<String, Object>> toolResultByCallId = new HashMap<>();
            if (hasToolResults) {
                for (Map<String, Object> tr : toolResults) {
                    Map<String, Object> toolCallMap = (Map<String, Object>) tr.get("toolCall");
                    if (toolCallMap != null && toolCallMap.get("id") != null) {
                        toolResultByCallId.put((String) toolCallMap.get("id"), tr);
                    }
                }
            }

            // Save individual tool results to tool_results table and build resultId map
            Map<String, String> resultIdByCallId = new HashMap<>();
            if (hasToolResults) {
                for (Map<String, Object> tr : toolResults) {
                    Map<String, Object> toolCallMap = (Map<String, Object>) tr.get("toolCall");
                    if (toolCallMap == null) continue;

                    String toolCallId = (String) toolCallMap.get("id");
                    String toolName = (String) toolCallMap.get("toolName");
                    boolean success = Boolean.TRUE.equals(tr.get("success"));
                    String resultContent = (String) tr.get("content");
                    String error = (String) tr.get("error");
                    Long durationMs = tr.get("durationMs") != null
                        ? ((Number) tr.get("durationMs")).longValue() : null;

                    if ((resultContent == null || resultContent.isEmpty()) && error != null) {
                        resultContent = error;
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolMetadata = tr.get("metadata") != null
                        ? (Map<String, Object>) tr.get("metadata") : null;

                    log.info("Saving tool result: tool={}, callId={}, success={}, contentLen={}, hasError={}, hasMeta={}",
                        toolName, toolCallId, success,
                        resultContent != null ? resultContent.length() : 0,
                        error != null, toolMetadata != null);

                    var savedResult = toolResultService.save(
                        conversationId, request.getUserId(),
                        toolName, toolCallId,
                        success, durationMs,
                        resultContent, error, toolMetadata
                    );

                    if (savedResult != null && toolCallId != null) {
                        resultIdByCallId.put(toolCallId, savedResult.getId().toString());
                        log.info("Saved tool result: id={}, callId={}, tool={}",
                            savedResult.getId(), toolCallId, toolName);
                    }

                    // Stage 4a.4 - record help-call observations so the JIT slim path
                    // can gate future turns' schema mode on whether this (tool, help)
                    // pair has been seen recently. No-op on non-help calls or failures;
                    // registry call is wrapped so a serialisation/Redis hiccup never
                    // drops the tool result persistence.
                    if (savedResult != null && success) {
                        maybeMarkHelpSeen(conversationId, toolName, toolCallMap.get("arguments"));
                    }
                }
            }

            // Build toolCalls JSON array using orderedEntries (chronological interleaving)
            List<Map<String, Object>> toolCallsList = new ArrayList<>();
            Set<String> processedToolCallIds = new HashSet<>();

            if (orderedEntries != null && !orderedEntries.isEmpty()) {
                int thinkingIndex = 0;
                for (Map<String, Object> entry : orderedEntries) {
                    String type = (String) entry.get("type");

                    if ("thinking".equals(type)) {
                        Map<String, Object> thinkingEntry = new LinkedHashMap<>();
                        thinkingEntry.put("toolName", "_thinking");
                        thinkingEntry.put("id", "_thinking_" + thinkingIndex++);
                        thinkingEntry.put("title", entry.get("title"));
                        thinkingEntry.put("thinkingMessage", entry.get("content"));
                        thinkingEntry.put("timestamp", entry.get("timestamp"));
                        thinkingEntry.put("success", true);
                        toolCallsList.add(thinkingEntry);

                    } else if ("tool_call".equals(type)) {
                        String toolCallId = (String) entry.get("id");
                        processedToolCallIds.add(toolCallId);

                        Map<String, Object> tr = toolResultByCallId.get(toolCallId);
                        Map<String, Object> toolEntry = buildToolCallEntry(
                            toolCallId, (String) entry.get("toolName"), entry.get("arguments"),
                            tr, resultIdByCallId.get(toolCallId), entry.get("timestamp"));
                        toolCallsList.add(toolEntry);
                    }
                }
            }

            // Add any remaining tool results not in orderedEntries (fallback)
            if (hasToolResults) {
                for (Map<String, Object> tr : toolResults) {
                    Map<String, Object> toolCallMap = (Map<String, Object>) tr.get("toolCall");
                    if (toolCallMap == null) continue;
                    String toolCallId = (String) toolCallMap.get("id");
                    if (toolCallId != null && processedToolCallIds.contains(toolCallId)) continue;

                    String toolName = (String) toolCallMap.get("toolName");
                    Map<String, Object> toolEntry = buildToolCallEntry(
                        toolCallId, toolName, toolCallMap.get("arguments"),
                        tr, resultIdByCallId.get(toolCallId), null);
                    toolCallsList.add(toolEntry);
                }
            }

            // Add _system_stop or _system_error marker
            String stopReason = response.stopReason();
            if ("STOPPED_BY_USER".equals(stopReason) || "CANCELLED".equals(stopReason)) {
                var savedStop = toolResultService.save(
                    conversationId, request.getUserId(),
                    "_system_stop", "call_system_stop",
                    true, 0L,
                    "Stream stopped by user", null
                );
                Map<String, Object> stopEntry = new LinkedHashMap<>();
                stopEntry.put("toolName", "_system_stop");
                stopEntry.put("id", "call_system_stop");
                stopEntry.put("success", true);
                stopEntry.put("durationMs", 0);
                if (savedStop != null) {
                    stopEntry.put("resultId", savedStop.getId().toString());
                }
                stopEntry.put("timestamp", System.currentTimeMillis());
                toolCallsList.add(stopEntry);
            } else if ("ERROR".equals(stopReason)) {
                var savedError = toolResultService.save(
                    conversationId, request.getUserId(),
                    "_system_error", "call_system_error",
                    false, 0L,
                    null, response.error()
                );
                Map<String, Object> errorEntry = new LinkedHashMap<>();
                errorEntry.put("toolName", "_system_error");
                errorEntry.put("id", "call_system_error");
                errorEntry.put("success", false);
                errorEntry.put("error", response.error());
                errorEntry.put("durationMs", 0);
                if (savedError != null) {
                    errorEntry.put("resultId", savedError.getId().toString());
                }
                errorEntry.put("timestamp", System.currentTimeMillis());
                toolCallsList.add(errorEntry);
            }

            // Add _meta entry with reasoningDurationMs
            Map<String, Object> metrics = response.metrics();
            long reasoningDurationMs = response.durationMs();
            if (metrics != null && metrics.get("reasoningDurationMs") != null) {
                reasoningDurationMs = ((Number) metrics.get("reasoningDurationMs")).longValue();
            }
            Map<String, Object> metaEntry = new LinkedHashMap<>();
            metaEntry.put("toolName", "_meta");
            metaEntry.put("reasoningDurationMs", reasoningDurationMs);
            toolCallsList.add(metaEntry);

            // Save assistant message with content + toolCalls JSON
            String toolCallsJson = null;
            if (!toolCallsList.isEmpty()) {
                toolCallsJson = objectMapper.writeValueAsString(toolCallsList);
            }

            MessageDto messageDto = new MessageDto();
            messageDto.setConversationId(conversationId);
            messageDto.setRole("assistant");
            messageDto.setContent(hasContent ? content : "");
            messageDto.setToolCalls(toolCallsJson);
            messageDto.setModel(response.model());
            messageDto.setTimestamp(java.time.Instant.now().toString());
            messageDto.setAgentId(request.getAgentId());
            // Stamp the execution id (== agent_executions.id) so the Conversation
            // Activity card can aggregate this turn by execution AND fetch the turn's
            // observability metrics (tokens / iterations / credits / status). Null-safe:
            // a caller that has no executionId passes null and grouping falls back to
            // the turn boundary.
            messageDto.setExecutionId(executionId);

            messageService.addMessage(conversationId, messageDto);

            log.info("Persisted remote execution results: conversationId={}, contentLen={}, toolEntries={}, thinkingSections={}",
                conversationId, content.length(), toolCallsList.size(),
                thinkingSections != null ? thinkingSections.size() : 0);

            // Compaction post-turn hook is dispatched centrally by MessageService.addMessage
            // (role=assistant chokepoint) - no explicit call needed here. Every surface
            // (chat / workflow-agent / standalone-agent / sub-agent) reaches compaction
            // through that one path.

        } catch (Exception e) {
            log.error("Failed to persist remote execution results for conversation {}: {}",
                conversationId, e.getMessage(), e);
        }
    }

    /**
     * Persist pending action to DB if the remote execution ended with a service approval request.
     * This ensures the approval card survives page refresh (the streaming event is transient).
     *
     * Handles two cases:
     * 1. request_credential tool → metadata contains serviceApprovalRequested=true
     * 2. approval_needed in tool content → tool result content contains {"status":"approval_needed"}
     */
    private void persistPendingActionIfNeeded(String conversationId, AgentExecutionResponseDto response) {
        try {
            // Previously persisted only the FIRST matching tool result and gated on
            // metrics.streamCompletedEarly. The async model raises approval/authorization
            // cards live without pausing the run, so a single turn can leave SEVERAL cards
            // waiting at once. Collect ALL of them (deduped by key) and merge into the
            // conversation's pending_actions list so every card survives a page refresh.
            List<Map<String, Object>> toolResults = response.toolResults();
            if (toolResults == null) return;

            List<Map<String, Object>> actions = new ArrayList<>();
            for (Map<String, Object> tr : toolResults) {
                Map<String, Object> action = extractPendingAction(tr);
                if (action != null) {
                    actions.add(action);
                }
            }

            if (!actions.isEmpty()) {
                pendingActionService.addPendingActions(conversationId, actions);
                log.info("Persisted {} pending action(s) for conversation {}", actions.size(), conversationId);
            }
        } catch (Exception e) {
            log.warn("Failed to persist pending action for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    /**
     * Extract the single pending action (approval/authorization) carried by one tool result,
     * or {@code null} when it carries none. Recognises the same three shapes as the agent-
     * service streaming callback: request_credential service approval, tool-authorization
     * metadata, and a soft {@code approval_needed} JSON in the content. Shared by the persist
     * and bridge live-emit paths so both stay aligned.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPendingAction(Map<String, Object> tr) {
        Map<String, Object> metadata = (Map<String, Object>) tr.get("metadata");

        // Case 1: request_credential tool with serviceApprovalRequested metadata.
        if (metadata != null && Boolean.TRUE.equals(metadata.get("serviceApprovalRequested"))) {
            List<Map<String, Object>> services = metadata.get("services") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
            if (!services.isEmpty()) {
                return PendingActionService.buildServiceApprovalAction(services,
                    (String) metadata.get("reason"),
                    Boolean.TRUE.equals(metadata.get("needsAttention")));
            }
        }

        // Case 1b: sensitive tool action gated by ToolAuthorizationGuard.
        if (metadata != null && Boolean.TRUE.equals(metadata.get("toolAuthorizationRequired"))) {
            String rule = (String) metadata.get("rule");
            if (rule != null && !rule.isBlank()) {
                return PendingActionService.buildToolAuthorizationAction(rule,
                    (String) metadata.get("toolName"),
                    (String) metadata.get("action"),
                    (String) metadata.get("toolCallId"),
                    (String) metadata.get("argsSummary"),
                    (String) metadata.get("applicationId"));
            }
        }

        // Case 2: approval_needed JSON in the tool content (soft credential warning).
        String content = (String) tr.get("content");
        if (content != null && content.contains("approval_needed")) {
            try {
                Map<String, Object> contentMap = objectMapper.readValue(content, Map.class);
                if ("approval_needed".equals(contentMap.get("status"))) {
                    Map<String, Object> serviceMap = new LinkedHashMap<>();
                    serviceMap.put("serviceType", contentMap.get("serviceType"));
                    serviceMap.put("serviceName", contentMap.get("serviceName"));
                    serviceMap.put("iconSlug", contentMap.getOrDefault("iconSlug", ""));
                    serviceMap.put("toolName", contentMap.getOrDefault("toolName", ""));
                    serviceMap.put("toolId", contentMap.getOrDefault("toolId", ""));
                    serviceMap.put("description", contentMap.getOrDefault("message", ""));
                    return PendingActionService.buildServiceApprovalAction(List.of(serviceMap),
                        "Credential required for " + contentMap.get("serviceName"), false);
                }
            } catch (Exception ignored) {
                // Content not valid JSON - skip.
            }
        }
        return null;
    }

    /**
     * Record a help-call observation in the {@link HelpSeenRegistry} when the
     * tool call's {@code action} is {@code "help"}. The turn number passed to
     * {@code markSeen} is the current count of {@link Message.MessageRole#USER}
     * messages in the conversation - each user prompt starts a new turn, and
     * the help freshness gate (HOT/WARM budget) counts turns from that anchor.
     *
     * <p>All exceptions are swallowed: a failure here must never break the
     * chat turn. The registry itself is best-effort (in-memory or Redis) and
     * the worst consequence of a missed mark is that the LLM re-helps on
     * the next turn - a cost, not a correctness bug.
     */
    private void maybeMarkHelpSeen(String conversationId, String toolName, Object argumentsRaw) {
        if (conversationId == null || conversationId.isBlank() || toolName == null || toolName.isBlank()) {
            return;
        }
        try {
            String argumentsJson;
            if (argumentsRaw == null) {
                argumentsJson = "";
            } else if (argumentsRaw instanceof String s) {
                argumentsJson = s;
            } else {
                argumentsJson = objectMapper.writeValueAsString(argumentsRaw);
            }
            if (!ToolCallClassifier.isHelpCall(toolName, argumentsJson)) {
                return;
            }
            long userTurns = messageRepository.countByConversationIdAndRole(
                    conversationId, Message.MessageRole.USER);
            int currentTurn = (int) Math.min(Integer.MAX_VALUE, userTurns);
            helpSeenRegistry.markSeen(conversationId, toolName, "help", currentTurn);
            log.debug("[HELP-SEEN] marked conv={} tool={} turn={}",
                    conversationId, toolName, currentTurn);
        } catch (Exception e) {
            log.warn("[HELP-SEEN] mark failed conv={} tool={}: {}",
                    conversationId, toolName, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildToolCallEntry(String toolCallId, String toolName,
                                                      Object arguments, Map<String, Object> toolResult,
                                                      String resultId, Object timestamp) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", toolCallId);
        entry.put("toolName", toolName);

        if (arguments != null) {
            try {
                entry.put("arguments", arguments instanceof String
                    ? arguments : objectMapper.writeValueAsString(arguments));
            } catch (Exception e) {
                entry.put("arguments", String.valueOf(arguments));
            }
        }

        if (resultId != null) {
            entry.put("resultId", resultId);
        }

        if (toolResult != null) {
            boolean success = Boolean.TRUE.equals(toolResult.get("success"));
            entry.put("success", success);
            entry.put("status", success ? "success" : "error");
            if (toolResult.get("durationMs") != null) {
                entry.put("durationMs", ((Number) toolResult.get("durationMs")).longValue());
            }
            if (toolResult.get("error") != null) {
                entry.put("error", toolResult.get("error"));
            }

            Map<String, Object> metadata = (Map<String, Object>) toolResult.get("metadata");
            if (metadata != null) {
                if (metadata.get("iconSlug") != null) entry.put("iconSlug", metadata.get("iconSlug"));
                if (metadata.get("toolName") != null) entry.put("displayToolName", metadata.get("toolName"));
                if (metadata.get("label") != null) entry.put("label", metadata.get("label"));
                if (metadata.get("visualization") != null) entry.put("visualization", metadata.get("visualization"));
                if (metadata.get("serviceApproval") != null) entry.put("serviceApproval", metadata.get("serviceApproval"));
                if (metadata.get("draftId") != null) entry.put("draftId", metadata.get("draftId"));
                // Source-tool render cards survive a refresh: red/green diff (repo edit/write/diff
                // + interface patch) and git status badges (repo git_status).
                if (metadata.get("diff") != null) entry.put("diff", metadata.get("diff"));
                if (metadata.get("gitStatus") != null) entry.put("gitStatus", metadata.get("gitStatus"));
            }
        } else {
            // No tool_result was emitted for this tool_call - the turn was interrupted
            // (stop, error, or budget exhaustion) before the tool ran. Persist as
            // 'interrupted' rather than 'pending' for two reasons:
            //  (1) honesty - the tool never executed, so 'error' would mislead readers
            //      and trigger red-error rendering paths that imply the tool failed;
            //  (2) the chat header reads `lastPendingActivity` from the persisted JSON,
            //      so any 'pending' here keeps the shimmer "Thinking…" on forever after
            //      refresh instead of switching to "Reasoning for X".
            entry.put("success", false);
            entry.put("status", "interrupted");
        }

        if (timestamp != null) {
            entry.put("timestamp", timestamp);
        }

        return entry;
    }

    // Credit consumption for chat has been moved to agent-service's recordFromChat()
    // to ensure per-execution credit tracking on the agent_executions table.

    // ========== Observability ==========

    private void recordObservability(ChatRequest request, AgentLoopContext context,
                                     AgentExecutionResponseDto response, String conversationId) {
        String agentId = resolveAgentId(request, context.credentials());

        try {
            AgentConfigProvider.AgentConfig agentConfig = null;
            if (agentId != null) {
                if (request.getOrgId() != null && !request.getOrgId().isBlank()) {
                    agentConfig = agentConfigProvider.getAgentConfig(agentId, request.getUserId(), request.getOrgId(), request.getOrgRole());
                } else {
                    agentConfig = agentConfigProvider.getAgentConfig(agentId, request.getUserId(), request.getOrgId());
                }
            }

            observabilityClient.recordAsync(
                request.getUserId(),
                // PR20 - workspace identity captured at the sync call boundary; the
                // @Async dispatch loses RequestContextHolder, so it must be threaded
                // explicitly. ChatControllerV3 populated this from X-Organization-ID.
                request.getOrgId(),
                agentId,
                response,
                context.systemPrompt(),
                context.userPrompt(),
                conversationId,
                agentConfig,
                request.getSource(),
                request.getTaskId(),
                // executionId - the stable dispatcher UUID flows from the outer
                // executeStreaming/executeSync mint sites through context.executionId().
                context.executionId()
            );
        } catch (Exception e) {
            log.warn("Failed to send observability for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    private String resolveAgentId(ChatRequest request, Map<String, Object> credentials) {
        String agentId = request.getAgentId();
        if ((agentId == null || agentId.isBlank()) && credentials != null) {
            Object credAgentId = credentials.get("__agentId__");
            if (credAgentId instanceof String s && !s.isBlank()) {
                agentId = s;
            }
        }
        return (agentId != null && !agentId.isBlank()) ? agentId : null;
    }

    // ========== Helpers ==========

    private Double fetchCreditBudget(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            java.math.BigDecimal balance = creditClient.fetchBalance(userId);
            return balance != null ? balance.doubleValue() : null;
        } catch (Exception e) {
            log.warn("Failed to fetch credit balance for remote budget, proceeding without: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Set the cancel key in Redis so the remote agent stops its loop.
     * Fire-and-forget: errors are logged but do not propagate.
     */
    private void setCancelKeyQuietly(String streamId) {
        if (streamId == null) return;
        try {
            stateService.setCancelKey(streamId).block();
            log.info("Set cancel key for remote agent after HTTP error: streamId={}", streamId);
        } catch (Exception ex) {
            log.warn("Failed to set cancel key for streamId={}: {}", streamId, ex.getMessage());
        }
    }

    private void handleExecutionError(Exception e, StreamingOutput streamOutput, String conversationId) {
        log.error("Error in agent execution: {}", e.getMessage(), e);
        if (streamOutput.isStreamProcessing()) {
            streamOutput.sendError("Agent execution error: " + e.getMessage());
        }

        // Persist an error assistant message so webhook/schedule poll can detect the failure
        if (conversationId != null) {
            try {
                MessageDto errorMsg = new MessageDto();
                errorMsg.setConversationId(conversationId);
                errorMsg.setRole("assistant");
                errorMsg.setContent("[Error] " + e.getMessage());
                errorMsg.setTimestamp(java.time.Instant.now().toString());
                messageService.addMessage(conversationId, errorMsg);
                log.info("Persisted error assistant message for conversation {}", conversationId);
            } catch (Exception persistErr) {
                log.warn("Failed to persist error message: {}", persistErr.getMessage());
            }
        }
    }

    // ===== Service Approval + Tool Authorization WS Notification (bridge path only) =====

    /**
     * Scan the agent response for a tool result that requests user approval and, if found,
     * publish the matching real-time event to the conversation's WS channel:
     * <ul>
     *   <li>{@code serviceApprovalRequested} metadata → a {@code service_approval_required}
     *       event (frontend discriminant: {@code 'services' in data && 'reason' in data}).</li>
     *   <li>{@code toolAuthorizationRequired} metadata → a {@code tool_authorization_required}
     *       event (frontend discriminant: {@code 'toolAuthorization' in data}).</li>
     * </ul>
     *
     * Called ONLY from the bridge execution path - the web-chat path already emits BOTH
     * events in real-time via {@code ConversationRedisStreamingCallback}. The pending action
     * is persisted separately ({@code persistPendingActionIfNeeded}) so the card also shows on
     * refresh; without THIS live emit the bridge card only appeared AFTER a refresh. The
     * tool-authorization branch mirrors {@code ConversationRedisStreamingCallback
     * .publishToolAuthorizationRequired} (it had only ever been wired for service approval).
     * Package-private for unit testing.
     */
    void emitPendingApprovalIfPresent(String conversationId, AgentExecutionResponseDto response) {
        if (conversationId == null) return;
        try {
            List<Map<String, Object>> toolResults = response.toolResults();
            if (toolResults == null) return;
            String channel = "ws:conversation:" + conversationId;
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> tr : toolResults) {
                Map<String, Object> action = extractPendingAction(tr);
                if (action == null) continue;
                // Dedup within the turn so a retried gated call does not double-emit. The
                // bridge publisher already emits each card live during the run; this end-of-
                // turn replay is the safety net for the tiny POST→WS-subscribe race window
                // (those live events are not buffered in the stream snapshot). The frontend
                // dedups by the same key, so a double delivery collapses to one card.
                if (!seen.add(pendingActionEmissionKey(action))) continue;

                String waitingFor = (String) action.get("waiting_for");
                Map<String, Object> event = new LinkedHashMap<>();
                if ("service_approval".equals(waitingFor)) {
                    // Frontend discriminant: 'services' + 'reason'.
                    event.put("services", action.get("services"));
                    Object reason = action.get("reason");
                    event.put("reason", reason != null && !String.valueOf(reason).isBlank()
                        ? reason : "Services need approval");
                    event.put("needsAttention", Boolean.TRUE.equals(action.get("needs_attention")));
                } else if ("tool_authorization".equals(waitingFor)) {
                    // Frontend discriminant: 'toolAuthorization'.
                    Map<String, Object> authorization = new LinkedHashMap<>();
                    authorization.put("rule", action.get("rule"));
                    authorization.put("toolName", action.get("tool_name"));
                    authorization.put("action", action.get("action"));
                    authorization.put("toolCallId", action.get("tool_call_id"));
                    authorization.put("argsSummary", action.get("args_summary"));
                    authorization.put("applicationId", action.get("application_id"));
                    event.put("toolAuthorization", authorization);
                } else {
                    continue;
                }
                event.put("timestamp", Instant.now().toString());
                eventBus.publish(channel, objectMapper.writeValueAsString(event));
                log.info("Published {} to WS (bridge path): conversation={}", waitingFor, conversationId);
            }
        } catch (Exception e) {
            log.debug("Failed to emit pending-approval WS event (non-critical): {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String pendingActionEmissionKey(Map<String, Object> action) {
        String waitingFor = (String) action.get("waiting_for");
        if (!"service_approval".equals(waitingFor)) {
            return PendingActionService.pendingActionKey(action);
        }
        Object servicesObj = action.get("services");
        String servicesKey = "";
        if (servicesObj instanceof List<?> services) {
            servicesKey = services.stream()
                .filter(s -> s instanceof Map)
                .map(s -> String.valueOf(((Map<String, Object>) s).get("serviceType")))
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        }
        return PendingActionService.pendingActionKey(action) + ":" + servicesKey;
    }

    // ========== Fleet Activity Events (bridge path only) ==========

    /**
     * Publish a fleet activity event via EventBus for the bridge execution path.
     * The remote path (agent-service) publishes its own events via AgentActivityPublisher.
     */
    private void publishFleetEvent(String agentEntityId, String executionId,
                                    String event, String model, String source, String taskId) {
        if (agentEntityId == null) return;
        try {
            Map<String, Object> payload = new HashMap<>(8);
            payload.put("event", event);
            payload.put("executionId", executionId);
            payload.put("agentEntityId", agentEntityId);
            payload.put("timestamp", Instant.now().toString());
            payload.put("model", model);
            payload.put("source", source);
            if (taskId != null && !taskId.isBlank()) {
                payload.put("taskId", taskId);
            }
            String channel = "ws:agent:activity:" + agentEntityId;
            eventBus.publish(channel, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to publish fleet activity event (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Publish a synthetic execution_started + execution_completed(FAILED) pair
     * for short-circuit failure paths (insufficient credits 402, gate denials)
     * that bypass {@link #executeSync} entirely and therefore never reach the
     * normal {@link #publishFleetEvent} call sites.
     *
     * <p>Without this, the Fleet view shows zero visual signal during throttled
     * fires - the user clicks into the canvas and wonders if anything is even
     * trying to run. The pair flashes the agent briefly (started → completed in
     * one tick) so the operator sees a fast pulse with the FAILED stop reason
     * on the activity card, matching the visual contract a normal execution
     * gets. Also bumps {@code completionSeq} on the frontend store, triggering
     * a metrics refetch so the new BUDGET_EXHAUSTED chip appears within the
     * same tick.
     */
    public void publishFleetFailureNoExecution(String agentEntityId, String model,
                                                String source, String taskId,
                                                String status, long durationMs) {
        if (agentEntityId == null || agentEntityId.isBlank()) return;
        String executionId = UUID.randomUUID().toString();
        publishFleetEvent(agentEntityId, executionId, "execution_started",
                model, source != null ? source : "CONVERSATION", taskId);
        publishFleetExecutionCompleted(agentEntityId, executionId,
                status != null ? status : "FAILED", 0, 0, durationMs, taskId);
    }

    private void publishFleetExecutionCompleted(String agentEntityId, String executionId,
                                                 String status, int totalTokens,
                                                 int totalToolCalls, long durationMs, String taskId) {
        if (agentEntityId == null) return;
        try {
            Map<String, Object> payload = new HashMap<>(8);
            payload.put("event", "execution_completed");
            payload.put("executionId", executionId);
            payload.put("agentEntityId", agentEntityId);
            payload.put("timestamp", Instant.now().toString());
            payload.put("status", status);
            payload.put("totalTokens", totalTokens);
            payload.put("totalToolCalls", totalToolCalls);
            payload.put("durationMs", durationMs);
            if (taskId != null && !taskId.isBlank()) {
                payload.put("taskId", taskId);
            }
            String channel = "ws:agent:activity:" + agentEntityId;
            eventBus.publish(channel, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to publish fleet execution_completed event (non-critical): {}", e.getMessage());
        }
    }
}
