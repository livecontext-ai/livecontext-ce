package com.apimarketplace.agent.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.metrics.AgentPrometheusMetrics;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import com.apimarketplace.agent.service.budget.BudgetReservationService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import com.apimarketplace.agent.domain.AgentExecutionIterationEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.service.dto.ChatAgentObservabilityRequest;
import com.apimarketplace.agent.service.dto.ChatObservabilityAdapter;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service responsible for recording full agent execution observability data.
 * Single @Transactional method that writes all 4 tables + updates agent counters.
 *
 * Entry points:
 * - recordFromRequest(): called via internal REST API from orchestrator (workflow/sub-agent/classify/guardrail)
 * - recordFromChat(): called via internal REST API from conversation-service (chat agents),
 *   delegates to the same doRecordFromRequest() via ChatObservabilityAdapter
 */
@Service
public class AgentObservabilityService {

    private static final Logger logger = LoggerFactory.getLogger(AgentObservabilityService.class);
    private static final int CONTENT_INLINE_THRESHOLD = 8192;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentExecutionRepository executionRepository;
    private final AgentExecutionIterationRepository iterationRepository;
    private final AgentExecutionMessageRepository messageRepository;
    private final AgentExecutionToolCallRepository toolCallRepository;
    private final StorageService storageService;
    private final CreditConsumptionClient creditClient;
    private final AgentMetricsAggregationService aggregationService;
    private final StorageBreakdownService breakdownService;
    private final com.apimarketplace.agent.repository.AgentRepository agentRepository;
    private final AgentPrometheusMetrics prometheusMetrics;
    private final BudgetReservationService budgetReservationService;

    /**
     * Read-only access to the claim log so the row writer can populate
     * {@code agent_executions.task_id} from the latest active claim on this
     * executionId when the caller didn't supply a taskId. See class doc on
     * {@link com.apimarketplace.agent.domain.AgentTaskClaimEntity}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.repository.AgentTaskClaimRepository claimRepository;

    /**
     * Used by the row writer to take the same {@code FOR KEY SHARE} lock on the task row that the
     * {@code agent_executions} FK INSERT would, so a task hard-deleted between agent dispatch and this
     * end-of-run record cannot be deleted between the check and the INSERT (closing the race that
     * would otherwise violate {@code fk_agent_executions_task_id} and abort the REQUIRES_NEW recording
     * transaction); a task already gone returns no row and the denormalised {@code task_id} is nulled.
     * Field-injected (required=false) to keep the constructor + its test call-sites untouched.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.repository.AgentTaskRepository taskRepository;

    /**
     * Optional product-analytics emitter (PostHog). Field-injected (required=false)
     * so the existing constructor and all its test call-sites are untouched, and so
     * the service still wires when analytics is disabled (the bean is then a no-op).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PostHogAnalyticsClient postHogAnalyticsClient;

    /**
     * Optional fleet-stats cache evictor. Field-injected (required=false) so the
     * constructor and all its test call-sites stay untouched, and so the service still
     * wires where the cache bean is absent. When present, a finished agent execution
     * drops the workspace's cached {@code /agents/stats} payload so the fleet badges
     * reflect the just-completed run on the next open instead of waiting for the TTL.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FleetStatsCacheService fleetStatsCacheService;

    /**
     * Optional run-cost notifier. Field-injected (required=false) so the
     * constructor and all its test call-sites stay untouched. When present, a
     * settled agent execution that belongs to a workflow run tells the
     * orchestrator its credit cost, so the run-mode UI can live-update
     * "Cost of this run" and enforce the workflow budget at the epoch boundary.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RunCostNotifier runCostNotifier;

    public AgentObservabilityService(
            AgentExecutionRepository executionRepository,
            AgentExecutionIterationRepository iterationRepository,
            AgentExecutionMessageRepository messageRepository,
            AgentExecutionToolCallRepository toolCallRepository,
            StorageService storageService,
            CreditConsumptionClient creditClient,
            AgentMetricsAggregationService aggregationService,
            StorageBreakdownService breakdownService,
            com.apimarketplace.agent.repository.AgentRepository agentRepository,
            AgentPrometheusMetrics prometheusMetrics,
            BudgetReservationService budgetReservationService) {
        this.executionRepository = executionRepository;
        this.iterationRepository = iterationRepository;
        this.messageRepository = messageRepository;
        this.toolCallRepository = toolCallRepository;
        this.storageService = storageService;
        this.creditClient = creditClient;
        this.aggregationService = aggregationService;
        this.breakdownService = breakdownService;
        this.agentRepository = agentRepository;
        this.prometheusMetrics = prometheusMetrics;
        this.budgetReservationService = budgetReservationService;
    }

    // ==========================================================================
    // Record from AgentObservabilityRequest DTO (called by orchestrator via REST)
    // ==========================================================================

    /**
     * Record full observability data from an AgentObservabilityRequest DTO.
     * This is the unified entry point for workflow agents, sub-agents, classify, and guardrail executions.
     * The orchestrator converts its internal types (Agent, ExecutionContext, etc.) to this DTO
     * and sends it via internal REST API.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordFromRequest(AgentObservabilityRequest request) {
        UUID executionId = null;
        try {
            executionId = doRecordFromRequest(request);
            logger.info("Recorded agent observability from request: executionId={}, nodeId={}, agentType={}",
                executionId, request.getNodeId(), request.getAgentType());
        } catch (Exception e) {
            logger.error("Failed to record agent observability from request: nodeId={}, error={}",
                request.getNodeId(), e.getMessage(), e);
        }

        // Consume credits regardless of observability success (sync to capture creditsUsed).
        // Always consume - even 0-token executions incur platform cost (LLM API call was made).
        String sourceType = resolveSourceType(request.getAgentType());
        String sourceId = executionId != null ? executionId.toString() : request.getNodeId();
        // Cascade reservation settle amount (§4.5 AGENT_BUDGET_HIERARCHY.md). Captured after
        // credit consumption so we pass the REAL cost to settleReservationChain. Defaults to
        // ZERO on credit consumption failure → settle refunds the full reservation so the
        // ancestor chain isn't left holding dead reservations.
        BigDecimal actualForSettle = BigDecimal.ZERO;
        try {
            int promptTok = (int) request.getPromptTokens();
            int completionTok = (int) request.getCompletionTokens();

            // Safety net: if prompt/completion breakdown is missing but totalTokens is set,
            // split 50/50. This should no longer happen for classify/guardrail (they now
            // provide real prompt/completion split via AgentLoopService) but is kept as
            // defense-in-depth for unexpected callers.
            if (promptTok == 0 && completionTok == 0 && request.getTotalTokens() > 0) {
                int total = (int) request.getTotalTokens();
                promptTok = total / 2;
                completionTok = total - promptTok; // handles odd numbers
                logger.warn("Token split fallback triggered: totalTokens={} split 50/50 - " +
                    "caller should provide prompt/completion breakdown. nodeId={}, agentType={}",
                    total, request.getNodeId(), request.getAgentType());
            }

            // Cache-aware billing: forward the cache/reasoning counters so auth-service
            // bills cache reads/writes at the provider's true relative price (e.g.
            // Anthropic read 0.1x / write 1.25x) instead of full input rate.
            Map<String, Object> creditResult = creditClient.consumeCredits(
                request.getTenantId(),
                sourceType,
                sourceId,
                request.getProvider(),
                request.getModel(),
                promptTok,
                completionTok,
                new com.apimarketplace.common.credit.LlmCacheTokens(
                    (int) request.getCacheCreationTokens(),
                    (int) request.getCacheReadTokens(),
                    (int) request.getCachedTokens(),
                    (int) request.getReasoningTokens())
            );

            // Track credits consumed on the agent entity + execution record
            if (creditResult != null) {
                // Soft rejection (e.g. 402 insufficient credits) - consumeCredits returns
                // {success=false, error="..."} without throwing. Persist to dead-letter so the
                // token usage we already burned isn't lost from the audit trail. Without this
                // branch a 402 only emitted a WARN log (the "last chat consumption not recorded"
                // prod incident: 2M Opus tokens with no ledger + no dead-letter row).
                Object successObj = creditResult.get("success");
                if (Boolean.FALSE.equals(successObj)) {
                    String rejectionReason = String.valueOf(creditResult.getOrDefault("error", "unknown rejection"));
                    creditClient.persistRejection(request.getTenantId(), sourceType, sourceId,
                            request.getProvider(), request.getModel(),
                            promptTok, completionTok, rejectionReason);
                }
                Object creditsUsedObj = creditResult.get("creditsUsed");
                if (creditsUsedObj instanceof Number creditsUsedNum) {
                    BigDecimal creditsUsed = BigDecimal.valueOf(creditsUsedNum.doubleValue());
                    // Capture actual cost for cascade settle, even when == 0 (still flows to
                    // settle so the reservation is refunded in full).
                    actualForSettle = creditsUsed;
                    if (creditsUsed.compareTo(BigDecimal.ZERO) > 0) {
                        // Record to Prometheus (Stage 5.4: tagged by source_type so
                        // COMPACTION_SUMMARY can be segregated in Grafana panel #10)
                        prometheusMetrics.recordCreditsConsumed(
                                request.getProvider(), request.getModel(),
                                sourceType, creditsUsed.doubleValue());
                        // Store on execution record
                        if (executionId != null) {
                            try {
                                executionRepository.updateCreditsConsumed(executionId, creditsUsed);
                            } catch (Exception ex) {
                                logger.warn("Failed to update credits on execution {}: {}",
                                    executionId, ex.getMessage());
                            }
                        }
                        // Accumulate on agent entity
                        if (request.getAgentEntityId() != null) {
                            try {
                                agentRepository.incrementCreditsConsumed(request.getAgentEntityId(), creditsUsed);
                            } catch (Exception ex) {
                                logger.warn("Failed to increment credits consumed for agent {}: {}",
                                    request.getAgentEntityId(), ex.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Credit consumption failed for agent execution nodeId={}, persisting to dead-letter: {}",
                request.getNodeId(), e.getMessage());
            persistToDeadLetter(request.getTenantId(), sourceType, sourceId,
                    request.getProvider(), request.getModel(),
                    (int) request.getPromptTokens(), (int) request.getCompletionTokens(), e.getMessage());
        }

        // Cascade reservation settle (§4.5 AGENT_BUDGET_HIERARCHY.md). When the SubAgent
        // handler transferred settle ownership (by setting callerChain + reservedAmount on
        // the request), refund/debit each ancestor in the chain atomically. A settle failure
        // logs-and-swallows - startup cleanup acts as the secondary safety net, and we must
        // not mask the successful credit consumption with a reservation bookkeeping error.
        List<UUID> callerChain = request.getCallerChain();
        BigDecimal reservedAmount = request.getReservedAmount();
        if (budgetReservationService != null
                && callerChain != null
                && !callerChain.isEmpty()
                && reservedAmount != null
                && reservedAmount.signum() > 0) {
            try {
                budgetReservationService.settleReservationChain(
                    callerChain, reservedAmount, actualForSettle);
                logger.debug("Settled cascade reservation for chain={} reserved={} actual={}",
                    callerChain, reservedAmount, actualForSettle);
            } catch (Exception settleEx) {
                logger.error("Failed to settle cascade reservation for chain={} reserved={} actual={}: {}",
                    callerChain, reservedAmount, actualForSettle, settleEx.getMessage(), settleEx);
            }
        }

        emitAgentRunStoppedAnalytics(request, executionId, actualForSettle);

        // Attribute this execution's cost to its workflow run so the run-mode UI
        // can live-update "Cost of this run" and the workflow budget can gate the
        // next epoch. Only workflow-run agents carry a runId; chat/standalone
        // executions have none and are skipped. Fully best-effort (fire-and-forget).
        if (runCostNotifier != null && request.getRunId() != null && !request.getRunId().isBlank()) {
            runCostNotifier.notifyRunCost(
                    request.getRunId(),
                    request.getOrganizationId(),
                    request.getTenantId(),
                    request.getEpoch(),
                    actualForSettle);
        }

        return executionId;
    }

    /**
     * Fire a summarized {@code agent_run_stopped} product-analytics event (PostHog).
     * Fully fire-and-forget: no-op unless analytics is configured, never throws,
     * never blocks the caller. Emits UUID / enum / count properties only - no PII,
     * no prompt or tool-output text.
     */
    private void emitAgentRunStoppedAnalytics(AgentObservabilityRequest request, UUID executionId, BigDecimal creditsConsumed) {
        if (postHogAnalyticsClient == null || !postHogAnalyticsClient.isActive()) return;
        try {
            postHogAnalyticsClient.capture(
                    request.getTenantId(),
                    "agent_run_stopped",
                    buildAgentRunStoppedProps(request, executionId, creditsConsumed));
        } catch (Exception e) {
            logger.debug("[posthog] failed building agent_run_stopped event (dropped): {}", e.toString());
        }
    }

    /**
     * Builds the PII-free property map for the {@code agent_run_stopped} event.
     * Package-private + static so it can be unit-tested without constructing the
     * full service. Emits enums / counts / UUIDs only - never tenant_id (that is
     * the distinct_id, passed separately), and never prompt/output text.
     */
    static Map<String, Object> buildAgentRunStoppedProps(AgentObservabilityRequest request, UUID executionId, BigDecimal creditsConsumed) {
        String stopReason = request.getStopReason();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("status", request.getStatus());
        props.put("stop_reason", stopReason);
        if (stopReason != null) {
            props.put("terminal_category", AgentStopReason.valueOfOrError(stopReason).terminal().name());
        }
        props.put("budget_scope", request.getBudgetScope());
        props.put("agent_type", request.getAgentType());
        props.put("provider", request.getProvider());
        props.put("model", request.getModel());
        props.put("iteration_count", request.getIterationCount());
        props.put("total_tool_calls", request.getTotalToolCalls());
        props.put("total_tokens", request.getTotalTokens());
        props.put("prompt_tokens", request.getPromptTokens());
        props.put("completion_tokens", request.getCompletionTokens());
        props.put("reasoning_tokens", request.getReasoningTokens());
        props.put("duration_ms", request.getDurationMs());
        props.put("credits_consumed", creditsConsumed != null ? creditsConsumed.doubleValue() : 0.0);
        props.put("node_id", request.getNodeId());
        props.put("organization_id", request.getOrganizationId());
        if (request.getWorkflowRunId() != null) props.put("workflow_run_id", request.getWorkflowRunId().toString());
        if (request.getWorkflowId() != null) props.put("workflow_id", request.getWorkflowId().toString());
        if (executionId != null) props.put("agent_execution_id", executionId.toString());
        return props;
    }

    /**
     * Map a (success, stopReason) pair to the persisted execution status.
     *
     * <p>Driven by {@link AgentStopReason#terminal()} (the contract-generated
     * {@code TerminalCategory}) so the bucket assignment stays aligned with the
     * shared stop-reason contract: SUCCESS → COMPLETED, PARTIAL → COMPLETED with
     * {@code stop_reason} explaining why, FAILURE → FAILED. STOPPED_BY_USER and
     * the system-level CANCELLED collapse to CANCELLED so the UI can show a
     * dedicated "cancelled" state.</p>
     *
     * <p>Unknown {@code stopReason} values fall back to {@link AgentStopReason#ERROR}.
     * The raw {@code stopReason} is always preserved verbatim in the
     * {@code stop_reason} column for forensics.</p>
     */
    private static String resolveExecutionStatus(boolean success, String stopReason, long totalTokens) {
        if (success) return "COMPLETED";
        AgentStopReason reason = AgentStopReason.valueOfOrError(stopReason);
        if (reason == AgentStopReason.STOPPED_BY_USER || reason == AgentStopReason.CANCELLED) {
            return "CANCELLED";
        }
        return switch (reason.terminal()) {
            case SUCCESS -> "COMPLETED";
            // Partial outcomes - agent produced usable output, just not the full result.
            // Treat as COMPLETED at the status level; the stop_reason column carries the
            // detail (MAX_ITERATIONS, BUDGET_EXHAUSTED, LOOP_DETECTED, TIMEOUT, …).
            //
            // EXCEPTION (BUDGET_EXHAUSTED-only): the conversation-service sync 402 path
            // records a row with success=false, stopReason=BUDGET_EXHAUSTED, totalTokens=0
            // to mean "agent never started, wallet was empty at the gate". That is a
            // clean FAILED, not a COMPLETED-with-warning. Narrowly scoped to
            // BUDGET_EXHAUSTED so other PARTIAL reasons that legitimately reach
            // totalTokens=0 (TIMEOUT before first LLM call, MAX_ITERATIONS with empty
            // first turn, LOOP_DETECTED on a 0-call loop) keep their legacy COMPLETED
            // mapping - AgentMetricsQueryService excludes TIMEOUT from failure_count,
            // so flipping it to FAILED would make 0-token TIMEOUTs vanish from both
            // success and failure tallies.
            case PARTIAL -> (reason == AgentStopReason.BUDGET_EXHAUSTED && totalTokens == 0)
                    ? "FAILED" : "COMPLETED";
            case FAILURE -> "FAILED";
        };
    }

    private String resolveSourceType(String agentType) {
        if (agentType == null) return "AGENT_EXECUTION";
        return switch (agentType.toLowerCase()) {
            case "classify" -> "CLASSIFY_EXECUTION";
            case "guardrail" -> "GUARDRAIL_EXECUTION";
            // Stage 5.4 - COLD summariser calls are charged to the tenant but
            // they are not user-visible agent turns; give them their own source
            // so Grafana panel #10 + the credit-ledger UI can segregate them
            // from primary agent cost.
            case "compaction_summary", "cold_summary" -> "COMPACTION_SUMMARY";
            // Browser-agent runs (LLM-driven Chromium sessions): per-session
            // cost is dominated by visual context tokens (screenshots), wall
            // clock is multi-minute, and the cap is per-session not per-turn.
            // Segregating in the credit-ledger lets users see "I burned $X on
            // browser sessions today" separately from chat agent spend.
            case "browser_agent" -> "BROWSER_AGENT_EXECUTION";
            // CLI/bridge sessions (claude-code/codex/gemini run via the agent-cli bridge).
            // These carry zero billed tokens (the external CLI pays its own provider), but
            // giving them their own source keeps the analytics/credit-ledger segmentation
            // honest instead of dumping them into the generic AGENT_EXECUTION bucket.
            case "cli" -> "CLI_SESSION";
            default -> "AGENT_EXECUTION";
        };
    }

    private UUID doRecordFromRequest(AgentObservabilityRequest request) {
        String tenantId = request.getTenantId();
        // PR20 - strict-isolation workspace identity. NULL = personal scope,
        // non-null = org workspace. The producer (orchestrator / conversation-
        // service) populates this from ExecutionContext.organizationId (workflow
        // path) or X-Organization-ID (chat path). Stamped on the header AND
        // mirrored onto child rows below so the UI history panel can scope
        // its listing by strict isolation.
        String organizationId = request.getOrganizationId();

        // 1. Build and save execution header
        AgentExecutionEntity exec = new AgentExecutionEntity();
        // Use the dispatcher-minted executionId as the PK so MCP-side claim log rows
        // (written via AgentTaskService.claimTask keyed by __executionId__) align
        // with the persisted agent_executions.id at end-of-run. Without this, the
        // claim log carries the dispatch UUID and the row carries a Hibernate-generated
        // UUID - the two never line up and task_id stays NULL (the 2026-05-22 bug class).
        if (request.getExecutionId() != null && !request.getExecutionId().isBlank()) {
            try {
                exec.setId(UUID.fromString(request.getExecutionId()));
            } catch (IllegalArgumentException invalidUuid) {
                logger.warn("Ignoring malformed executionId={} on observability record (falling back to auto-gen)",
                        request.getExecutionId());
            }
        }
        exec.setTenantId(tenantId);
        exec.setOrganizationId(organizationId);
        exec.setAgentType(request.getAgentType());
        exec.setNodeId(request.getNodeId());

        if (request.getWorkflowId() != null) {
            exec.setWorkflowId(request.getWorkflowId());
        }
        if (request.getWorkflowRunId() != null) {
            exec.setWorkflowRunId(request.getWorkflowRunId());
        }
        exec.setRunId(request.getRunId());
        exec.setEpoch(request.getEpoch());
        exec.setSpawn(request.getSpawn());
        exec.setItemIndex(request.getItemIndex());
        exec.setLoopIteration(request.getLoopIteration());

        // Source: explicit override > sub-agent > workflow default
        String resolvedSource;
        if (request.getSource() != null && !request.getSource().isBlank()) {
            resolvedSource = request.getSource();
        } else if (request.getCallerAgentId() != null) {
            resolvedSource = "SUB_AGENT";
        } else {
            resolvedSource = "WORKFLOW";
        }
        exec.setSource(resolvedSource);
        if (request.getCallerAgentId() != null) {
            exec.setCallerAgentEntityId(request.getCallerAgentId());
            exec.setDepth(request.getNestingDepth());
        }

        // Link to agent entity
        if (request.getAgentEntityId() != null) {
            exec.setAgentEntityId(request.getAgentEntityId());
        }

        // LLM config snapshot
        exec.setProvider(request.getProvider());
        exec.setModel(request.getModel());
        if (request.getTemperature() != null) {
            exec.setTemperature(BigDecimal.valueOf(request.getTemperature()));
        }
        exec.setMaxTokensConfig(request.getMaxTokensConfig());
        exec.setMaxIterationsConfig(request.getMaxIterationsConfig());
        exec.setAgentConfigSnapshot(buildAgentConfigSnapshot(request, resolvedSource));

        // Outcome - resolve status using stop reason (e.g. STOPPED_BY_USER → CANCELLED, not FAILED)
        boolean success = "COMPLETED".equals(request.getStatus());
        exec.setStatus(resolveExecutionStatus(success, request.getStopReason(), request.getTotalTokens()));
        exec.setStopReason(request.getStopReason());
        exec.setBudgetScope(request.getBudgetScope());
        exec.setErrorMessage(request.getErrorMessage());
        exec.setDurationMs(request.getDurationMs());
        exec.setEndedAt(Instant.now());

        // Token usage (cast long -> int: token counts fit in int range)
        exec.setTotalPromptTokens((int) request.getPromptTokens());
        exec.setTotalCompletionTokens((int) request.getCompletionTokens());
        exec.setTotalTokens((int) request.getTotalTokens());
        exec.setTotalCacheCreationTokens((int) request.getCacheCreationTokens());
        exec.setTotalCacheReadTokens((int) request.getCacheReadTokens());
        exec.setTotalCachedTokens((int) request.getCachedTokens());
        exec.setTotalReasoningTokens((int) request.getReasoningTokens());

        // Iteration count
        exec.setIterationCount(request.getIterationCount());

        // Tool call counters
        exec.setTotalToolCalls(request.getTotalToolCalls());
        exec.setLoopDetected(request.isLoopDetected());
        exec.setLoopType(request.getLoopType());
        exec.setLoopToolName(request.getLoopToolName());
        exec.setSystemPrompt(request.getSystemPrompt());
        exec.setMemoryEnabled(request.getMemoryEnabled());
        if (request.getConversationId() != null) {
            exec.setConversationId(request.getConversationId());
        }
        if (request.getParentConversationId() != null) {
            exec.setParentConversationId(request.getParentConversationId());
        }
        if (request.getTaskId() != null) {
            exec.setTaskId(request.getTaskId());
        } else if (request.getExecutionId() != null && !request.getExecutionId().isBlank() && claimRepository != null) {
            // Fallback: when the workflow/chat caller didn't supply a taskId (the
            // schedule-fire path can't - the agent picks the task via MCP after
            // dispatch), look up the claim log for the latest active claim on this
            // executionId. Closes the 2026-05-22 prod bug where the row was written
            // with task_id=NULL because the linkage happened in MCP-land, not on
            // the dispatcher side.
            try {
                UUID execUuid = UUID.fromString(request.getExecutionId());
                claimRepository.findLatestActiveClaim(execUuid)
                        .ifPresent(c -> exec.setTaskId(c.getTaskId()));
            } catch (IllegalArgumentException ignored) {
                // malformed executionId - already logged at exec.setId() above
            }
        }

        // Resilience: a task can be hard-deleted between agent dispatch and this end-of-run record
        // (e.g. a user deletes a task whose agent is still mid-execution, or a fast "provider not
        // configured" failure races the delete). The FK fk_agent_executions_task_id is ON DELETE SET
        // NULL for the delete-AFTER-insert case, but an INSERT referencing an already-gone task still
        // violates it and aborts this REQUIRES_NEW transaction, poisoning the metrics/storage writes
        // below. Before the save, take the SAME FOR KEY SHARE lock on the task row that the FK INSERT
        // would take: if the task exists the lock is held until this transaction commits, so the task
        // cannot be deleted between here and the INSERT - the race is CLOSED, not merely narrowed (a
        // concurrent delete blocks on the lock, then runs its ON DELETE SET NULL after we commit). If
        // the task is already gone, no lock is taken and we null the denormalised link: task_id is
        // nullable and null is the intended post-delete state, so the execution still records with its
        // metrics intact, just unlinked.
        UUID linkedTaskId = exec.getTaskId();
        if (linkedTaskId != null && taskRepository != null && taskRepository.lockTaskRowIfExists(linkedTaskId).isEmpty()) {
            logger.debug("Task {} no longer exists; recording execution with task_id=null", linkedTaskId);
            exec.setTaskId(null);
        }

        // Save execution header
        executionRepository.save(exec);
        UUID executionId = exec.getId();

        // 2. Save iteration records
        if (request.getIterations() != null && !request.getIterations().isEmpty()) {
            saveIterationsFromRequest(executionId, tenantId, organizationId, request.getIterations());
        }

        // 3. Save messages
        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            saveMessagesFromRequest(executionId, tenantId, organizationId, request.getMessages());
            exec.setMessageCount(request.getMessages().size());
        }

        // 4. Save tool calls
        List<AgentExecutionToolCallEntity> savedToolCalls = null;
        if (request.getToolCalls() != null && !request.getToolCalls().isEmpty()) {
            savedToolCalls = saveToolCallsFromRequest(executionId, tenantId, organizationId, request.getToolCalls());
            // Compute tool stats
            int successCount = 0;
            int failCount = 0;
            List<String> toolNames = new ArrayList<>();
            for (var tc : request.getToolCalls()) {
                if (tc.isSuccess()) successCount++;
                else failCount++;
                toolNames.add(tc.getToolName() != null ? tc.getToolName() : "unknown");
            }
            exec.setSuccessfulToolCalls(successCount);
            exec.setFailedToolCalls(failCount);
            exec.setToolSequence(String.join(",", toolNames));
            exec.setDistinctTools(new ArrayList<>(new LinkedHashSet<>(toolNames)));
        }

        // Re-save with updated message/tool stats
        executionRepository.save(exec);

        // 5. Update agent entity counters (use resolved status from exec, not raw request)
        if (request.getAgentEntityId() != null) {
            try {
                String resolvedStatus = exec.getStatus();
                boolean isCancelled = "CANCELLED".equals(resolvedStatus);
                executionRepository.incrementCounters(
                    request.getAgentEntityId(),
                    request.getTotalTokens(),
                    request.getTotalToolCalls(),
                    "COMPLETED".equals(resolvedStatus) ? 1 : 0,
                    (!"COMPLETED".equals(resolvedStatus) && !isCancelled) ? 1 : 0,
                    request.getDurationMs(),
                    Instant.now()
                );
            } catch (Exception e) {
                logger.warn("Counter update failed for agent {}: {}", request.getAgentEntityId(), e.getMessage());
            }
        }

        // 6. Update aggregation tables
        try {
            boolean isSuccess = "COMPLETED".equals(exec.getStatus());
            aggregationService.updateAggregations(tenantId, organizationId, request.getAgentEntityId(),
                request.getProvider(), request.getModel(), isSuccess,
                request.getStopReason(), request.isLoopDetected(), request.getDurationMs(),
                request.getTotalTokens(), request.getTotalToolCalls(),
                request.getIterationCount(), savedToolCalls);
        } catch (Exception e) {
            logger.warn("Aggregation update failed for execution {}: {}", executionId, e.getMessage());
        }

        // 6b. Org-aware sub-agent rollup (option D) - THIS execution as the CALLEE. Fed from
        // the callee's resolved status (COMPLETED/FAILED; CANCELLED/TIMEOUT count toward
        // neither), matching getAllSubAgentCallStats which counts callee execution rows by
        // status - NOT from the caller's 'agent' tool-call success (a different event/grain).
        // No-op unless this execution was spawned by a caller (request.getCallerAgentId()).
        try {
            aggregationService.recordSubAgentCallFromCallee(
                organizationId, tenantId, request.getCallerAgentId(), request.getAgentEntityId(), exec.getStatus());
        } catch (Exception e) {
            logger.warn("Sub-agent-org rollup failed for execution {}: {}", executionId, e.getMessage());
        }

        // 7. Record Prometheus metrics
        try {
            prometheusMetrics.recordExecution(
                    request.getProvider(), request.getModel(), request.getAgentType(),
                    "COMPLETED".equals(exec.getStatus()), request.getDurationMs(),
                    request.getPromptTokens(), request.getCompletionTokens(),
                    request.getTotalToolCalls(), request.getIterationCount(),
                    request.isLoopDetected());
        } catch (Exception e) {
            logger.warn("Prometheus metrics recording failed for execution {}: {}", executionId, e.getMessage());
        }

        // 8. Track storage breakdown
        try {
            long agentDataSize = estimateRequestSize(request);
            breakdownService.trackSave(tenantId, "AGENTS", agentDataSize);
        } catch (Exception e) {
            logger.warn("Storage breakdown tracking failed for execution {}: {}", executionId, e.getMessage());
        }

        // 9. Drop the workspace's fleet-stats cache so this run's ✓/✗ shows on the next
        //    fleet open. Only when the run belongs to a real agent (agent_entity_id) -
        //    the four fleet aggregations all filter agent_entity_id IS NOT NULL, so
        //    general-chat rows (null agent) never change the fleet badges. Best-effort:
        //    the cache service swallows Redis failures, and the guard keeps it cheap.
        if (fleetStatsCacheService != null
                && organizationId != null && !organizationId.isBlank()
                && request.getAgentEntityId() != null) {
            fleetStatsCacheService.evict(organizationId);
        }

        return executionId;
    }

    private Map<String, Object> buildAgentConfigSnapshot(AgentObservabilityRequest request, String resolvedSource) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (request.getAgentConfigSnapshot() != null && !request.getAgentConfigSnapshot().isEmpty()) {
            snapshot.putAll(request.getAgentConfigSnapshot());
        }
        putIfAbsent(snapshot, "provider", request.getProvider());
        putIfAbsent(snapshot, "model", request.getModel());
        putIfAbsent(snapshot, "temperature", request.getTemperature());
        putIfAbsent(snapshot, "maxTokens", request.getMaxTokensConfig());
        putIfAbsent(snapshot, "maxIterations", request.getMaxIterationsConfig());
        putIfAbsent(snapshot, "systemPrompt", request.getSystemPrompt());
        putIfAbsent(snapshot, "memoryEnabled", request.getMemoryEnabled());
        putIfAbsent(snapshot, "source", resolvedSource);
        if (request.getAgentEntityId() != null) {
            putIfAbsent(snapshot, "agentEntityId", request.getAgentEntityId().toString());
        }
        return snapshot.isEmpty() ? null : snapshot;
    }

    private static void putIfAbsent(Map<String, Object> snapshot, String key, Object value) {
        if (value != null && !snapshot.containsKey(key)) {
            snapshot.put(key, value);
        }
    }

    private void saveIterationsFromRequest(UUID executionId, String tenantId, String organizationId,
                                            List<AgentObservabilityRequest.IterationData> iterations) {
        List<AgentExecutionIterationEntity> entities = new ArrayList<>();
        for (int i = 0; i < iterations.size(); i++) {
            var iterData = iterations.get(i);
            AgentExecutionIterationEntity iter = new AgentExecutionIterationEntity();
            iter.setExecutionId(executionId);
            iter.setTenantId(tenantId);
            iter.setOrganizationId(organizationId);
            iter.setIterationNumber(iterData.getIterationNumber());
            iter.setFinal(i == iterations.size() - 1);
            iter.setToolCallCount(iterData.getToolCallCount());
            // Only set token values when non-zero (preserve null semantics for missing data)
            if (iterData.getPromptTokens() != 0) iter.setPromptTokens((int) iterData.getPromptTokens());
            if (iterData.getCompletionTokens() != 0) iter.setCompletionTokens((int) iterData.getCompletionTokens());
            if (iterData.getCacheCreationTokens() != 0) iter.setCacheCreationTokens((int) iterData.getCacheCreationTokens());
            if (iterData.getCacheReadTokens() != 0) iter.setCacheReadTokens((int) iterData.getCacheReadTokens());
            if (iterData.getCachedTokens() != 0) iter.setCachedTokens((int) iterData.getCachedTokens());
            if (iterData.getReasoningTokens() != 0) iter.setReasoningTokens((int) iterData.getReasoningTokens());
            iter.setDurationMs(iterData.getDurationMs());
            iter.setFinishReason(iterData.getFinishReason());
            entities.add(iter);
        }
        iterationRepository.saveAll(entities);
    }

    private void saveMessagesFromRequest(UUID executionId, String tenantId, String organizationId,
                                          List<AgentObservabilityRequest.MessageData> messages) {
        List<AgentExecutionMessageEntity> entities = new ArrayList<>();
        int iterationCounter = 0;
        for (int seq = 0; seq < messages.size(); seq++) {
            var msgData = messages.get(seq);
            AgentExecutionMessageEntity entity = new AgentExecutionMessageEntity();
            entity.setExecutionId(executionId);
            entity.setTenantId(tenantId);
            entity.setOrganizationId(organizationId);
            entity.setSequenceNumber(seq);
            entity.setRole(msgData.getRole());
            // Use DTO-provided iterationNumber if available, otherwise compute locally
            if (msgData.getIterationNumber() != null) {
                entity.setIterationNumber("SYSTEM".equalsIgnoreCase(msgData.getRole()) ? null : msgData.getIterationNumber());
            } else {
                if ("ASSISTANT".equalsIgnoreCase(msgData.getRole())) {
                    iterationCounter++;
                }
                entity.setIterationNumber("SYSTEM".equalsIgnoreCase(msgData.getRole()) ? null : iterationCounter);
            }
            applyContentStorage(entity, msgData.getContent(), tenantId);
            entity.setToolCallId(msgData.getToolCallId());
            entity.setToolName(msgData.getToolName());
            entities.add(entity);
        }
        messageRepository.saveAll(entities);
    }

    private List<AgentExecutionToolCallEntity> saveToolCallsFromRequest(UUID executionId, String tenantId,
                                                                         String organizationId,
                                                                         List<AgentObservabilityRequest.ToolCallData> toolCalls) {
        List<AgentExecutionToolCallEntity> entities = new ArrayList<>();
        String prevToolName = null;
        String prevArgsJson = null;
        int consecutiveCount = 0;

        for (int seq = 0; seq < toolCalls.size(); seq++) {
            var tcData = toolCalls.get(seq);
            AgentExecutionToolCallEntity entity = new AgentExecutionToolCallEntity();
            entity.setExecutionId(executionId);
            entity.setTenantId(tenantId);
            entity.setOrganizationId(organizationId);
            entity.setSequenceNumber(seq);
            entity.setIterationNumber(tcData.getIterationNumber());
            entity.setToolCallId(tcData.getToolCallId());
            entity.setToolName(tcData.getToolName() != null ? tcData.getToolName() : "unknown");
            entity.setParallelIndex(tcData.getParallelIndex());
            entity.setArguments(tcData.getArguments());
            entity.setSuccess(tcData.isSuccess());
            entity.setErrorMessage(tcData.getErrorMessage());
            entity.setDurationMs(tcData.getDurationMs());
            // Strip any heavy vision-media bytes before persisting: the base64 belongs to the
            // vision channel only and would bloat the JSONB observability row.
            entity.setMetadata(ToolMediaMetadata.withoutHeavyMedia(tcData.getMetadata()));

            // Content with storage strategy
            applyToolCallContentStorage(entity, tcData.getResult(), tenantId);

            // Estimated token counts from content size
            String argsStr = serializeArgs(tcData.getArguments());
            if (argsStr != null && !argsStr.isEmpty()) {
                entity.setEstimatedInputTokens(argsStr.length() / 4);
            }
            if (tcData.getResult() != null && !tcData.getResult().isEmpty()) {
                entity.setEstimatedOutputTokens(tcData.getResult().length() / 4);
            }

            // Repeat detection
            String currentToolName = entity.getToolName();
            String currentArgsJson = serializeArgs(tcData.getArguments());

            if (Objects.equals(currentToolName, prevToolName) && Objects.equals(currentArgsJson, prevArgsJson)) {
                consecutiveCount++;
                entity.setRepeat(true);
                entity.setConsecutiveCount(consecutiveCount);
            } else {
                consecutiveCount = 1;
                entity.setRepeat(false);
                entity.setConsecutiveCount(1);
            }

            prevToolName = currentToolName;
            prevArgsJson = currentArgsJson;

            entities.add(entity);
        }

        toolCallRepository.saveAll(entities);
        return entities;
    }

    private long estimateRequestSize(AgentObservabilityRequest request) {
        long size = 0;
        if (request.getMessages() != null) {
            for (var m : request.getMessages()) {
                if (m.getContent() != null) size += m.getContent().length();
            }
        }
        if (request.getToolCalls() != null) {
            for (var tc : request.getToolCalls()) {
                if (tc.getResult() != null) size += tc.getResult().length();
                if (tc.getArguments() != null) size += tc.getArguments().toString().length();
            }
        }
        return size;
    }

    // ==========================================================================
    // Record from chat (called by conversation-service via internal REST)
    // Delegates to the unified doRecordFromRequest() via ChatObservabilityAdapter.
    // ==========================================================================

    /**
     * Record observability data for a chat-originated agent execution.
     * Called via internal REST endpoint from conversation-service.
     * Converts the chat-specific DTO into the unified AgentObservabilityRequest
     * and delegates to the same recording path used by workflow agents.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFromChat(String tenantId, String organizationId, ChatAgentObservabilityRequest request) {
        // Convert to unified request via adapter (source is set in the adapter).
        // PR20 - organizationId resolved from inbound X-Organization-ID by the
        // controller; threaded through here so the persisted row carries the
        // workspace identity the call came in on.
        AgentObservabilityRequest unified = ChatObservabilityAdapter.toUnifiedRequest(tenantId, organizationId, request);

        UUID executionId = null;
        try {
            executionId = doRecordFromRequest(unified);

            logger.info("Recorded chat agent observability: executionId={}, agentId={}, tokens={}, source={}",
                executionId, request.agentEntityId(), request.totalTokens(), unified.getSource());
        } catch (Exception e) {
            logger.error("Failed to record chat agent observability: agentId={}, error={}",
                request.agentEntityId(), e.getMessage(), e);
        }

        // Consume credits with CHAT_CONVERSATION source type (different from workflow's AGENT_EXECUTION).
        // Always consume - even 0-token chat executions incur platform cost.
        // Exception: failure-only rows (the conversation-service sync 402/error paths now
        // record agent_executions rows with success=false AND 0 tokens, exclusively for
        // dashboard visibility - no LLM call happened, so no consumeCredits attempt is
        // warranted. Without this guard, every throttled cron tick would POST a 0-token
        // consume that produces persistRejection ledger noise for an already-throttled
        // tenant.
        if (!request.success()
                && request.totalPromptTokens() == 0
                && request.totalCompletionTokens() == 0) {
            logger.info("Skipping consumeCredits for failure-only observability row: executionId={}, agentId={}, stopReason={}",
                    executionId, request.agentEntityId(), request.stopReason());
            // Failure-only chat row (e.g. budget gate) is still an agent run - emit it
            // (no LLM call happened, so zero credits).
            emitAgentRunStoppedAnalytics(unified, executionId, BigDecimal.ZERO);
            return;
        }
        BigDecimal chatCreditsConsumed = BigDecimal.ZERO;
        String chatSourceId = executionId != null ? executionId.toString() : request.conversationId();
        try {
            // Cache-aware billing - same forwarding as recordFromRequest above.
            Map<String, Object> creditResult = creditClient.consumeCredits(
                tenantId,
                "CHAT_CONVERSATION",
                chatSourceId,
                request.provider(),
                request.model(),
                request.totalPromptTokens(),
                request.totalCompletionTokens(),
                new com.apimarketplace.common.credit.LlmCacheTokens(
                    request.totalCacheCreationTokens(),
                    request.totalCacheReadTokens(),
                    request.totalCachedTokens(),
                    request.totalReasoningTokens())
            );

            if (creditResult != null) {
                // Soft rejection (402 insufficient credits) - persist to dead-letter so the
                // chat's token consumption survives the audit trail. See twin branch in
                // recordFromRequest above; this is the bridge-chat code path that dropped
                // a 2M-token Opus turn without a ledger/dead-letter row in prod.
                Object successObj = creditResult.get("success");
                if (Boolean.FALSE.equals(successObj)) {
                    String rejectionReason = String.valueOf(creditResult.getOrDefault("error", "unknown rejection"));
                    creditClient.persistRejection(tenantId, "CHAT_CONVERSATION", chatSourceId,
                            request.provider(), request.model(),
                            request.totalPromptTokens(), request.totalCompletionTokens(), rejectionReason);
                }
                Object creditsUsedObj = creditResult.get("creditsUsed");
                if (creditsUsedObj instanceof Number creditsUsedNum) {
                    BigDecimal creditsUsed = BigDecimal.valueOf(creditsUsedNum.doubleValue());
                    chatCreditsConsumed = creditsUsed;
                    if (creditsUsed.compareTo(BigDecimal.ZERO) > 0) {
                        if (executionId != null) {
                            try {
                                executionRepository.updateCreditsConsumed(executionId, creditsUsed);
                            } catch (Exception ex) {
                                logger.warn("Failed to update credits on execution {}: {}", executionId, ex.getMessage());
                            }
                        }
                        if (request.agentEntityId() != null) {
                            try {
                                agentRepository.incrementCreditsConsumed(
                                    UUID.fromString(request.agentEntityId()), creditsUsed);
                            } catch (Exception ex) {
                                logger.warn("Failed to increment credits on agent {}: {}", request.agentEntityId(), ex.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Credit consumption failed for chat execution {}, persisting to dead-letter: {}",
                executionId, e.getMessage());
            persistToDeadLetter(tenantId, "CHAT_CONVERSATION", chatSourceId,
                    request.provider(), request.model(),
                    request.totalPromptTokens(), request.totalCompletionTokens(), e.getMessage());
        }

        emitAgentRunStoppedAnalytics(unified, executionId, chatCreditsConsumed);
    }

    // ==========================================================================
    // Content storage helpers
    // ==========================================================================

    private void applyContentStorage(AgentExecutionMessageEntity entity, String content, String tenantId) {
        if (content == null) {
            entity.setContentLength(0);
            return;
        }

        entity.setContentLength(content.length());

        if (content.length() > CONTENT_INLINE_THRESHOLD) {
            try {
                UUID storageId = storageService.saveText(tenantId, content, "agent_message.txt",
                    "text/plain", null);
                entity.setContentStorageId(storageId);
                entity.setContent(content.substring(0, 500) + "...[truncated]");
            } catch (Exception e) {
                logger.warn("Failed to store large message content, inlining truncated: {}", e.getMessage());
                entity.setContent(content.substring(0, Math.min(content.length(), CONTENT_INLINE_THRESHOLD)));
            }
        } else {
            entity.setContent(content);
        }
    }

    private void applyToolCallContentStorage(AgentExecutionToolCallEntity entity, String content, String tenantId) {
        if (content == null) {
            entity.setContentLength(0);
            return;
        }

        entity.setContentLength(content.length());

        if (content.length() > CONTENT_INLINE_THRESHOLD) {
            try {
                UUID storageId = storageService.saveText(tenantId, content, "tool_call_result.txt",
                    "text/plain", null);
                entity.setContentStorageId(storageId);
                entity.setContent(content.substring(0, 500) + "...[truncated]");
            } catch (Exception e) {
                logger.warn("Failed to store large tool call content, inlining truncated: {}", e.getMessage());
                entity.setContent(content.substring(0, Math.min(content.length(), CONTENT_INLINE_THRESHOLD)));
            }
        } else {
            entity.setContent(content);
        }
    }

    private String serializeArgs(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(arguments);
        } catch (Exception e) {
            return arguments.toString();
        }
    }

    /**
     * Persist failed credit consumption to the dead-letter table via CreditConsumptionClient.
     * Uses the client's configured CreditDeadLetterHandler (HttpCreditDeadLetterHandler).
     * Swallows all exceptions - dead-letter persistence is best-effort.
     *
     * <p>DECIDED POLICY (cache-aware billing, 2026-06-11): only prompt/completion
     * survive into the dead-letter row - the cache breakdown is dropped, so an
     * eventual replay bills at the legacy full-rate formula (expensive for
     * bridge providers whose promptTokens include cache, cheap for direct-API
     * providers whose cache counters are additive). Accepted for this rare
     * degraded path; same posture as the conversation-service fallback debit.</p>
     */
    private void persistToDeadLetter(String tenantId, String sourceType, String sourceId,
                                      String provider, String model,
                                      int promptTokens, int completionTokens, String errorReason) {
        try {
            // Delegate to the async path which has built-in dead-letter support.
            // consumeCreditsAsync retries up to 3 times, then persists to dead-letter.
            creditClient.consumeCreditsAsync(tenantId, sourceType, sourceId,
                    provider, model, promptTokens, completionTokens);
        } catch (Exception e) {
            logger.error("Dead-letter persistence also failed for {}/{}: {}",
                    sourceType, sourceId, e.getMessage());
        }
    }
}
