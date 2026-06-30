package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wait node - Delays execution for a specified duration.
 *
 * For durations <= 3 seconds: inline Thread.sleep() (too short for DB overhead).
 * For durations > 3 seconds: registers a signal and YIELDS (non-blocking).
 *   The engine returns immediately, and execution resumes when the timer expires
 *   via UnifiedSignalService polling.
 *
 * Usage:
 * - Add delays between steps
 * - Rate limiting
 * - Waiting for external processes
 */
public class WaitNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(WaitNode.class);
    private static final long INLINE_THRESHOLD_MS = 3_000;

    private final long durationMs;

    // Injected via ExecutionServiceInjector (null = inline-only mode, backward compatible)
    private UnifiedSignalService signalService;
    private Clock clock;
    private String dagTriggerId;
    private int epoch;
    /** F2.4 - optional cancel-signal source for inline waits. Null in unit tests. */
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    public WaitNode(String nodeId, long durationMs) {
        super(nodeId, NodeType.WAIT);
        this.durationMs = durationMs;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("⏳ Wait node executing: nodeId={}, durationMs={}, itemId={}",
            nodeId, durationMs, context.itemId());

        try {
            // Short wait: inline Thread.sleep (too short for DB overhead)
            if (durationMs <= INLINE_THRESHOLD_MS || signalService == null) {
                return executeInline(context);
            }

            // Long wait: register signal and YIELD (non-blocking)
            return executeWithSignal(context);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("⚠️ Wait interrupted: nodeId={}", nodeId);
            java.util.Map<String, Object> failOutput = new java.util.HashMap<>();
            failOutput.put("node_type", "WAIT");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", buildInputData());
            failOutput.put("error", "Wait interrupted");
            return NodeExecutionResult.failureWithOutput(nodeId, "Wait interrupted", failOutput, 0L);
        } catch (Exception e) {
            logger.error("❌ Wait execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            java.util.Map<String, Object> failOutput = new java.util.HashMap<>();
            failOutput.put("node_type", "WAIT");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", buildInputData());
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    /**
     * Inline wait: blocks the thread for short durations (<= 3s).
     *
     * <p>F2.4 - sliced into 100ms chunks polling the run's cancel signal so a
     * STOP arriving during the wait releases the thread within ~100ms instead
     * of the full duration. Without this, even short waits hold the worker
     * past the user's STOP - across a fork of N short waits, that compounds.
     */
    private NodeExecutionResult executeInline(ExecutionContext context) throws InterruptedException {
        Clock clk = clock != null ? clock : Clock.systemUTC();
        Instant startedAt = clk.instant();

        if (durationMs > 0) {
            logger.debug("⏳ Wait node inline sleep: nodeId={}, durationMs={}", nodeId, durationMs);
            String runId = context.runId();
            long remaining = durationMs;
            while (remaining > 0) {
                long slice = Math.min(100L, remaining);
                TimeUnit.MILLISECONDS.sleep(slice);
                remaining -= slice;
                if (workflowRedisPublisher != null && runId != null
                        && workflowRedisPublisher.isAgentCancelSignalSet(runId)) {
                    logger.info("⏹️ [CANCEL] Wait inline aborted by run cancel signal: nodeId={}, remainingMs={}",
                        nodeId, remaining);
                    Map<String, Object> cancelOutput = new java.util.HashMap<>();
                    cancelOutput.put("node_type", "WAIT");
                    cancelOutput.put("item_index", context.itemIndex());
                    cancelOutput.put("itemIndex", context.itemIndex());
                    cancelOutput.put("item_id", context.itemId());
                    cancelOutput.put("resolved_params", buildInputData());
                    cancelOutput.put("cancelled", true);
                    return NodeExecutionResult.failureWithOutput(nodeId,
                        "Wait cancelled (run cancel signal)", cancelOutput, durationMs - remaining);
                }
            }
        }

        Map<String, Object> result = buildOutput(context, startedAt, clk.instant());
        logger.info("✅ Wait completed (inline): nodeId={}, durationMs={}", nodeId, durationMs);
        return NodeExecutionResult.success(nodeId, result);
    }

    /**
     * Signal-based wait: registers a timer signal and YIELDS.
     * The engine returns immediately. Execution resumes when the timer expires.
     */
    private NodeExecutionResult executeWithSignal(ExecutionContext context) {
        String runId = context.runId();
        String itemId = context.itemId();

        // Resolve runtime signal context (dagTriggerId from plan, epoch from context/default)
        String effectiveDagTriggerId = SignalContextResolver.resolveDagTriggerId(nodeId, dagTriggerId, context);
        int effectiveEpoch = SignalContextResolver.resolveEpoch(epoch, context);

        Map<String, Object> signalConfig = SignalConfig.timer(durationMs);
        Clock clk = clock != null ? clock : Clock.systemUTC();

        // Split context: persist the current item alongside the signal (same as
        // UserApprovalNode) so per-item timers expose their item and the split
        // context can be rehydrated on resume/restart.
        Map<String, Object> splitItemData = SignalContextResolver.buildSplitItemData(context);

        signalService.registerSignal(
            runId, itemId, nodeId, effectiveDagTriggerId, effectiveEpoch,
            SignalType.WAIT_TIMER, signalConfig, splitItemData);

        Instant startedAt = clk.instant();
        String startedAtStr = startedAt.toString();
        String expiresAt = startedAt.plusMillis(durationMs).toString();
        logger.info("⏳ Wait registered signal (yield): nodeId={}, durationMs={}, expiresAt={}",
            nodeId, durationMs, expiresAt);

        Map<String, Object> signalOutput = new java.util.HashMap<>();
        signalOutput.put("resolved_params", buildInputData());
        signalOutput.put("duration_ms", durationMs);
        signalOutput.put("started_at", startedAtStr);
        signalOutput.put("expires_at", expiresAt);
        return NodeExecutionResult.awaitingSignal(nodeId, SignalType.WAIT_TIMER, signalOutput);
    }

    private Map<String, Object> buildInputData() {
        Map<String, Object> inputData = new java.util.LinkedHashMap<>();
        inputData.put("duration", durationMs);
        return inputData;
    }

    private Map<String, Object> buildOutput(ExecutionContext context, Instant startedAt, Instant completedAt) {
        return buildWaitOutput(durationMs, startedAt, completedAt, context.itemId(), context.itemIndex());
    }

    /**
     * #W1: single source of truth for the Wait node's documented output contract.
     * Called by both the inline path and the signal-resume path (via
     * SignalResumeService), so `{{core:wait.output.*}}` refs resolve identically
     * regardless of whether the wait was short-circuited inline or yielded as a
     * WAIT_TIMER signal.
     *
     * @param durationMs  configured wait duration (published as waited_ms)
     * @param startedAt   wait start timestamp (may be null → started_at omitted)
     * @param completedAt wait end timestamp (may be null → completed_at omitted)
     * @param itemId      split-context item id (may be null)
     * @param itemIndex   split-context item index
     */
    public static Map<String, Object> buildWaitOutput(
            long durationMs, Instant startedAt, Instant completedAt,
            String itemId, int itemIndex) {
        Map<String, Object> result = new java.util.HashMap<>();
        Map<String, Object> inputData = new java.util.LinkedHashMap<>();
        inputData.put("duration", durationMs);
        result.put("resolved_params", inputData);
        result.put("status", "completed");
        result.put("waited_ms", durationMs);
        if (startedAt != null) result.put("started_at", startedAt.toString());
        if (completedAt != null) result.put("completed_at", completedAt.toString());
        result.put("node_type", "WAIT");
        result.put("item_index", itemIndex);
        result.put("itemIndex", itemIndex);
        result.put("item_id", itemId);
        return result;
    }

    public long getDurationMs() {
        return durationMs;
    }

    // ========================================================================
    // SERVICE INJECTION (via ExecutionServiceInjector)
    // ========================================================================

    public void setSignalService(UnifiedSignalService signalService) {
        this.signalService = signalService;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void setDagTriggerId(String dagTriggerId) {
        this.dagTriggerId = dagTriggerId;
    }

    public void setEpoch(int epoch) {
        this.epoch = epoch;
    }

    /**
     * Accepts services from the registry.
     * WaitNode needs signal services for non-blocking waits.
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.signalService = registry.getSignalService();
        this.clock = registry.getClock();
        this.workflowRedisPublisher = registry.getWorkflowRedisPublisher();
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder {
        private String nodeId;
        private long durationMs;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public WaitNode build() {
            return new WaitNode(nodeId, durationMs);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
