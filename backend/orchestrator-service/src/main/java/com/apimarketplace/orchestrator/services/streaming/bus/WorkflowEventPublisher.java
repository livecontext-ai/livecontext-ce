package com.apimarketplace.orchestrator.services.streaming.bus;

import com.apimarketplace.orchestrator.services.streaming.events.AgentToolCallEvent;
import com.apimarketplace.orchestrator.services.streaming.events.AgentToolCallPhase;
import com.apimarketplace.orchestrator.services.streaming.events.DebugLogEvent;
import com.apimarketplace.orchestrator.services.streaming.events.EdgeLifecycle;
import com.apimarketplace.orchestrator.services.streaming.events.EdgeStatusEvent;
import com.apimarketplace.orchestrator.services.streaming.events.LoopEvent;
import com.apimarketplace.orchestrator.services.streaming.events.LoopEventType;
import com.apimarketplace.orchestrator.services.streaming.events.MergeEvent;
import com.apimarketplace.orchestrator.services.streaming.events.MergeEventType;
import com.apimarketplace.orchestrator.services.streaming.events.RetryEvent;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import com.apimarketplace.orchestrator.services.streaming.events.StepStatusEvent;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowStatisticsEvent;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowStatusEvent;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowEvent;
import com.apimarketplace.orchestrator.services.streaming.events.SequencedEvent;
import com.apimarketplace.orchestrator.services.streaming.AsyncSnapshotPublisher;
import com.apimarketplace.orchestrator.services.streaming.SeqPublishLockStripes;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import com.apimarketplace.orchestrator.services.streaming.WsEventSequencer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Façade unique pour publier les événements Workflow vers le bus.
 */
@Component
public class WorkflowEventPublisher {

    private final WorkflowEventBus bus;
    private final boolean copyPayloads;

    @Autowired(required = false)
    private WorkflowRedisPublisher redisPublisher;

    /**
     * Phase A2 (archi-refoundation 2026-05-04) - sequencer that assigns a
     * monotonic seq to each event before Redis publish. Required-false to
     * keep tests bootable without the full Spring graph; production wiring
     * always provides one.
     */
    @Autowired(required = false)
    private WsEventSequencer wsEventSequencer;

    /**
     * Multi-trigger ordering guard (2026-05-05) - serialize the
     * {@code nextSeq → publishSequenced} window per runId so concurrent
     * trigger fires don't deliver events to the frontend with seq inverted.
     * See {@link SeqPublishLockStripes} for the full rationale.
     */
    @Autowired(required = false)
    private SeqPublishLockStripes seqPublishLockStripes;

    /**
     * Phase B.1 wiring (regression fix 2026-05-05) - every published event
     * must trigger a coalesced snapshot batch-update so the frontend
     * {@code statusCounts} layer keeps refreshing. Pre-fix: markDirty had
     * ZERO production callers, so PascalCase wire events fell through the
     * frontend router AND no batch-update arrived to repaint counters.
     *
     * <p>{@code @Lazy} breaks the bean cycle:
     * StateSnapshotService → WorkflowEventPublisher → SnapshotService →
     * StateSnapshotService. Spring injects a proxy that resolves the real
     * bean on first method call, well after context init. Required-false
     * also keeps narrow Spring tests bootable without the streaming graph.
     */
    @Lazy
    @Autowired(required = false)
    private SnapshotService snapshotService;

    /**
     * Plan v4 §8 wiring - routes the post-publish markDirty through a
     * bounded executor (5 threads, queue 100) when
     * {@code orchestrator.optim.async-publish=true} (default ON).
     * Decouples the event-publish hot path from the SSE/Redis publish
     * round-trip; on REJECTED (queue full) the publish is dropped + metric
     * incremented. When flag OFF, runs inline - same behavior as before
     * #8 ship.
     *
     * <p>{@code required=false} keeps narrow Spring tests bootable without
     * the AsyncSnapshotPublisher bean; null → fallback to direct
     * {@code snapshotService.markDirty} call.
     *
     * <p>{@code @Lazy} breaks the StateSnapshotService → WorkflowEventPublisher
     * → AsyncSnapshotPublisher → SnapshotService → StateSnapshotService cycle
     * by inserting a proxy here. Spring still injects the real bean on first
     * call, but defers wiring until after the cycle's beans are constructed.
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private AsyncSnapshotPublisher asyncSnapshotPublisher;

    /**
     * Wire-format event-type translation. Frontend
     * {@code INDIVIDUAL_EVENT_TYPES} (useWorkflowStreaming.ts) routes by
     * lowercase/camelCase keys; the previous {@code getClass().getSimpleName()}
     * leaked PascalCase, which silently fell through to the batch-update
     * path and was dropped (no snapshot fields).
     */
    private static final Map<String, String> EVENT_TYPE_WIRE_NAMES = Map.ofEntries(
        Map.entry("StepStatusEvent", "stepStatus"),
        Map.entry("EdgeStatusEvent", "edgeStatus"),
        Map.entry("WorkflowStatusEvent", "workflowStatus"),
        Map.entry("WorkflowStatisticsEvent", "workflowStatistics"),
        Map.entry("LoopEvent", "loopEvent"),
        Map.entry("RetryEvent", "retryEvent"),
        Map.entry("DebugLogEvent", "debugLog"),
        Map.entry("MergeEvent", "mergeEvent"),
        Map.entry("AgentToolCallEvent", "agentToolCall")
    );

    static String wireEventType(Class<?> eventClass) {
        String simple = eventClass.getSimpleName();
        return EVENT_TYPE_WIRE_NAMES.getOrDefault(simple, lowerFirst(simple));
    }

    private static String lowerFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    public WorkflowEventPublisher(WorkflowEventBus bus,
                                  @Value("${workflow.streaming.copy-payloads:true}") boolean copyPayloads) {
        this.bus = bus;
        this.copyPayloads = copyPayloads;
    }

    public void emitStep(String runId,
                         String normalizedStepId,
                         Map<String, Object> payload,
                         StepLifecycle lifecycle) {
        StepStatusEvent event = new StepStatusEvent(
            runId,
            normalizedStepId,
            sanitizePayload(payload),
            lifecycle != null ? lifecycle : StepLifecycle.RUNNING,
            now()
        );
        publish(event);
    }

    public void emitStep(String runId,
                         String normalizedStepId,
                         Map<String, Object> payload) {
        emitStep(runId, normalizedStepId, payload, StepLifecycle.RUNNING);
    }

    public void emitEdge(String runId,
                         String edgeId,
                         String from,
                         String to,
                         EdgeLifecycle lifecycle) {
        emitEdge(runId, edgeId, from, to, lifecycle, null);
    }

    public void emitEdge(String runId,
                         String edgeId,
                         String from,
                         String to,
                         EdgeLifecycle lifecycle,
                         Integer itemIndex) {
        emitEdge(runId, edgeId, from, to, lifecycle, itemIndex, null);
    }

    public void emitEdge(String runId,
                         String edgeId,
                         String from,
                         String to,
                         EdgeLifecycle lifecycle,
                         Integer itemIndex,
                         Integer iteration) {
        EdgeStatusEvent event = new EdgeStatusEvent(
            runId,
            edgeId,
            from,
            to,
            lifecycle,
            itemIndex,
            iteration,
            now()
        );
        publish(event);
    }

    public void emitWorkflowStatus(String runId,
                                   String status,
                                   String message,
                                   Map<String, Object> payload,
                                   boolean terminal) {
        WorkflowStatusEvent event = new WorkflowStatusEvent(
            runId,
            status,
            message,
            sanitizePayload(payload),
            now(),
            terminal
        );
        publish(event);
    }

    public void emitWorkflowStatistics(String runId,
                                       Map<String, Object> payload) {
        WorkflowStatisticsEvent event = new WorkflowStatisticsEvent(
            runId,
            sanitizePayload(payload),
            now()
        );
        publish(event);
    }

    public void emitLoopEvent(String runId,
                              String loopId,
                              LoopEventType type,
                              Map<String, Object> payload) {
        LoopEvent event = new LoopEvent(runId, loopId, type, sanitizePayload(payload), now());
        publish(event);
    }

    public void emitRetryEvent(String runId,
                               String stepId,
                               long itemId,
                               int retryIndex,
                               String cause,
                               Map<String, Object> payload) {
        RetryEvent event = new RetryEvent(runId, stepId, itemId, retryIndex, cause, sanitizePayload(payload), now());
        publish(event);
    }

    public void emitDebugLog(String runId, String level, String message) {
        DebugLogEvent event = new DebugLogEvent(runId, level, message, now());
        publish(event);
    }

    public void emitMergeEvent(String runId,
                               String mergeId,
                               MergeEventType type,
                               Map<String, Object> payload) {
        MergeEvent event = new MergeEvent(runId, mergeId, type, sanitizePayload(payload), now());
        publish(event);
    }

    /**
     * Emit an agent tool call event for real-time tracking.
     *
     * @param runId      The workflow run ID
     * @param nodeId     The agent node ID
     * @param toolName   The name of the tool being called
     * @param toolCallId The unique ID of this tool call
     * @param phase      The phase of the tool call (CALLING, COMPLETED, FAILED)
     * @param payload    Additional data (arguments, result, error)
     * @param itemIndex  The trigger item index
     * @param iteration  The loop iteration (if applicable)
     */
    public void emitAgentToolCall(String runId,
                                  String nodeId,
                                  String toolName,
                                  String toolCallId,
                                  AgentToolCallPhase phase,
                                  Map<String, Object> payload,
                                  int itemIndex,
                                  Integer iteration) {
        AgentToolCallEvent event = new AgentToolCallEvent(
            runId,
            nodeId,
            toolName,
            toolCallId,
            phase,
            sanitizePayload(payload),
            itemIndex,
            iteration,
            now()
        );
        publish(event);
    }

    public void publish(WorkflowEvent event) {
        if (event == null) {
            return;
        }

        // In-JVM bus consumers receive the immutable inner event unchanged
        // (record-typed deps in WorkflowEventProcessor, etc.). The seq is
        // a wire-format concern for the Redis → WebSocket → frontend path.
        bus.publish(event);

        // Phase A2: Redis publish carries the monotonic seq (wire-format flat).
        String runId = event.runId();
        if (redisPublisher != null) {
            // Per-runId lock keeps the assign-then-publish window atomic so
            // concurrent triggers on the same runId can't deliver events with
            // seq inverted (the frontend strict-< stale filter would drop the
            // older one silently). See SeqPublishLockStripes for rationale.
            Runnable publishAction = () -> {
                String eventType = null;
                try {
                    eventType = wireEventType(event.getClass());
                    long seq = (wsEventSequencer != null) ? wsEventSequencer.nextSeq(runId) : -1L;
                    // Wrap in SequencedEvent record (publisher-internal contract)
                    // before unwrapping to flat wire format. Keeps the type system
                    // honest about which events go through the seq pipeline.
                    SequencedEvent<WorkflowEvent> sequenced = new SequencedEvent<>(event, seq);
                    redisPublisher.publishSequenced(runId, eventType, sequenced.inner(), sequenced.seq());
                } catch (Exception e) {
                    // Redis publish failure should never block the event bus, but it
                    // also should not be silent - without a log, debugging post-deploy
                    // (frontend stops receiving events) is blind. (Audit final.)
                    log.warn("[WorkflowEventPublisher] Redis publish failed for runId={}, type={}: {}",
                            runId, eventType, e.getMessage());
                }
            };
            if (seqPublishLockStripes != null) {
                seqPublishLockStripes.withRunIdLock(runId, publishAction);
            } else {
                publishAction.run();
            }
        }

        // Phase B.1 invariant: every event triggers a coalesced batch-update
        // so the frontend statusCounts layer keeps refreshing. SnapshotService
        // throttles (200 ms) + tombstones terminal runs internally; failure
        // must not block the bus.
        //
        // Plan v4 §8 (2026-05-11): route through AsyncSnapshotPublisher when
        // the bean is present (async-publish flag wiring), falling back to
        // direct snapshotService.markDirty when the publisher isn't wired
        // (narrow Spring tests, or production with flag OFF + null bean).
        if (runId != null) {
            try {
                if (asyncSnapshotPublisher != null) {
                    asyncSnapshotPublisher.publishAsync(runId);
                } else if (snapshotService != null) {
                    snapshotService.markDirty(runId);
                }
            } catch (Exception e) {
                log.warn("[WorkflowEventPublisher] post-publish markDirty failed for runId={}: {}",
                        runId, e.getMessage());
            }
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkflowEventPublisher.class);

    private long now() {
        return Instant.now().toEpochMilli();
    }

    private Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        if (!copyPayloads || payload == null || payload.isEmpty()) {
            return payload;
        }
        return new LinkedHashMap<>(payload);
    }
}
