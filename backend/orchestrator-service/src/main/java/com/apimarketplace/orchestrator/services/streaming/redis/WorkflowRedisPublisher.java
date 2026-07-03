package com.apimarketplace.orchestrator.services.streaming.redis;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.event.KeyValueStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Publishes workflow events to Pub/Sub channels for the Gateway WebSocket bridge.
 *
 * <p>Channel naming convention: {@code ws:workflow:run:{runId}}</p>
 * <p>User notification channel: {@code ws:user:{userId}:notifications}</p>
 * <p>Org notification channel: {@code ws:org:{orgId}:notifications} (PR25 -
 *    fan-out so org teammates see each other's notifications).</p>
 *
 * <p>Uses EventBus abstraction: Redis Pub/Sub in EE, in-memory in CE monolith.</p>
 */
@Component
public class WorkflowRedisPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRedisPublisher.class);
    private static final String CHANNEL_PREFIX = "ws:workflow:run:";
    private static final String NOTIFICATION_PREFIX = "ws:user:";
    private static final String ORG_NOTIFICATION_PREFIX = "ws:org:";

    private final EventBus eventBus;
    private final KeyValueStore keyValueStore;
    private final ObjectMapper objectMapper;

    public WorkflowRedisPublisher(EventBus eventBus, KeyValueStore keyValueStore, ObjectMapper objectMapper) {
        this.eventBus = eventBus;
        this.keyValueStore = keyValueStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish a workflow event to the run channel.
     *
     * <p>For Map payloads, automatically injects {@code type} and {@code runId} fields
     * (using putIfAbsent) so that the frontend WebSocket client can route events after
     * unwrapping the Redis envelope.</p>
     */
    public void publishEvent(String runId, String eventType, Object payload) {
        publishEvent(runId, eventType, payload, -1L);
    }

    /**
     * Phase A2 (archi-refoundation 2026-05-04) - overload that injects a
     * monotonic {@code seq} into the payload (Map only - for record payloads
     * use {@link #publishSequenced(String, String, Object, long)}). When
     * {@code seq < 0}, no seq field is injected (back-compat for callers that
     * don't yet plumb {@code WsEventSequencer}).
     */
    public void publishEvent(String runId, String eventType, Object payload, long seq) {
        if (runId == null) return;

        String channel = CHANNEL_PREFIX + runId;

        Object enrichedPayload = payload;
        if (payload instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> original = (Map<String, Object>) payload;
            Map<String, Object> enriched = new LinkedHashMap<>(original);
            enriched.putIfAbsent("type", eventType);
            enriched.putIfAbsent("runId", runId);
            if (seq >= 0) enriched.put("seq", seq);  // override any pre-existing seq
            enrichedPayload = enriched;
        }

        Map<String, Object> envelope = buildEnvelope(eventType, enrichedPayload);
        publish(channel, envelope);
    }

    /**
     * Publish a full snapshot (batch-update) to the run channel.
     */
    public void publishSnapshot(String runId, Map<String, Object> snapshot) {
        publishEvent(runId, "batch-update", snapshot);
    }

    /**
     * Phase A2 (archi-refoundation 2026-05-04) - publish a sequenced event.
     * Wire format is FLAT: {@code {seq, type, runId, ...inner-fields}}, NOT
     * the nested {@code {inner: {...}, seq}} that naïve Jackson serialization
     * would produce on a {@code SequencedEvent} record. The flat shape is
     * what the frontend already consumes - only the new {@code seq} field is
     * additive (frontend tolerates unknown fields, no zod/ajv validator).
     *
     * <p>Note: we cannot just {@code jacksonObject.convertValue} the wrapper
     * record - it would emit {@code {"inner": {...}, "seq": 42}}. Instead we
     * unwrap explicitly: the inner event is converted to a Map, then we
     * splice {@code seq} on top.
     *
     * @param runId   the public run identifier (channel key)
     * @param type    event type (typically {@code event.getClass().getSimpleName()})
     * @param inner   the immutable original event
     * @param seq     monotonic sequence number from {@code WsEventSequencer.nextSeq}
     */
    public void publishSequenced(String runId, String type, Object inner, long seq) {
        if (runId == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> innerMap = objectMapper.convertValue(inner, Map.class);
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("seq", seq);
        flat.put("type", type);
        flat.put("runId", runId);
        if (innerMap != null) {
            // putAll without overwriting seq/type/runId: those are authoritative
            for (Map.Entry<String, Object> e : innerMap.entrySet()) {
                flat.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        publishEvent(runId, type, flat);
    }

    /**
     * Publish a notification to a specific user's notification channel.
     */
    public void publishNotification(String userId, String kind, Object payload) {
        if (userId == null) return;

        String channel = NOTIFICATION_PREFIX + userId + ":notifications";
        Map<String, Object> envelope = buildEnvelope(kind, payload);
        publish(channel, envelope);
    }

    /**
     * PR25 - publish a notification to an org's notification channel so all
     * teammates of the org receive it. Used in tandem with
     * {@link #publishNotification(String, String, Object)} so the OWNER sees
     * it on their user channel AND every org member sees it on the org channel.
     *
     * <p>Subscription authorization at the gateway checks that the subscriber's
     * {@code WsSession.organizationId} matches the channel's {@code orgId}.</p>
     *
     * <p>Blank/null orgId is a no-op (personal-scope notifications never reach
     * an org channel - that's the load-bearing isolation guarantee).</p>
     */
    public void publishOrgNotification(String orgId, String kind, Object payload) {
        if (orgId == null || orgId.isBlank()) return;

        String channel = ORG_NOTIFICATION_PREFIX + orgId + ":notifications";
        Map<String, Object> envelope = buildEnvelope(kind, payload);
        publish(channel, envelope);
    }

    private Map<String, Object> buildEnvelope(String eventType, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", 1);
        envelope.put("type", eventType);
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("ts", System.currentTimeMillis());
        envelope.put("payload", payload);
        return envelope;
    }

    // ==================== Agent Cancel Signal ====================

    private static final String CANCEL_KEY_PREFIX = "workflow:cancel:";
    // 3-hour TTL: aligned with the max agent execution budget (7200s contract,
    // 125-min bridge cap) plus buffer. Previous 10-minute TTL could expire before
    // long-running agents checked shouldStop().
    private static final Duration CANCEL_KEY_TTL = Duration.ofHours(3);

    /**
     * Set a cancel signal for a workflow run. Agent-service checks this key
     * via RedisStreamingCallback.shouldStop() to interrupt mid-stream LLM calls.
     */
    public void setAgentCancelSignal(String runId) {
        if (runId == null) return;
        try {
            String key = CANCEL_KEY_PREFIX + runId;
            keyValueStore.set(key, "cancelled", CANCEL_KEY_TTL);
            log.info("[RedisPublisher] Set agent cancel signal for runId={}", runId);
        } catch (Exception e) {
            log.warn("[RedisPublisher] Failed to set cancel signal for runId={}: {}", runId, e.getMessage());
        }
    }

    /**
     * Check if the cancel signal is currently set for a run.
     * Used by the execution engine to abort traversal early.
     *
     * <p>F2.2 - also walks the parent chain via {@code workflow:parent:&#123;runId&#125;}
     * pointers so a cancel on the parent run cascades to any sub-workflow run
     * fired by {@code SubWorkflowNode}. Bounded by {@link #MAX_PARENT_WALK} to
     * defend against accidental cycles.
     *
     * @return true if the cancel key exists for this run OR any ancestor
     */
    public boolean isAgentCancelSignalSet(String runId) {
        if (runId == null) return false;
        String current = runId;
        int hops = 0;
        try {
            while (current != null && hops <= MAX_PARENT_WALK) {
                if (keyValueStore.get(CANCEL_KEY_PREFIX + current).isPresent()) {
                    return true;
                }
                Optional<String> parent = keyValueStore.get(PARENT_KEY_PREFIX + current);
                if (parent.isEmpty()) return false;
                current = parent.get();
                hops++;
            }
            return false;
        } catch (Exception e) {
            log.debug("[RedisPublisher] Failed to check cancel signal for runId={}: {}", runId, e.getMessage());
            return false; // fail-open: if Redis is down, don't block execution
        }
    }

    /**
     * Clear the cancel signal (e.g., when a run is re-triggered after stop).
     */
    public void clearAgentCancelSignal(String runId) {
        if (runId == null) return;
        try {
            keyValueStore.delete(CANCEL_KEY_PREFIX + runId);
            log.debug("[RedisPublisher] Cleared agent cancel signal for runId={}", runId);
        } catch (Exception e) {
            log.warn("[RedisPublisher] Failed to clear cancel signal for runId={}: {}", runId, e.getMessage());
        }
    }

    // ==================== Sub-workflow parent link (F2.2) ====================

    private static final String PARENT_KEY_PREFIX = "workflow:parent:";
    /** Same TTL as cancel keys - sub-runs that outlive 3h are treated as detached anyway. */
    private static final Duration PARENT_KEY_TTL = Duration.ofHours(3);
    /** Defensive depth cap on the parent walk to short-circuit accidental cycles. */
    private static final int MAX_PARENT_WALK = 10;

    /**
     * F2.2 - record that {@code childRunId} is a sub-run of {@code parentRunId}.
     * The engine's {@link #isAgentCancelSignalSet(String)} walks this pointer
     * upward, so cancelling the parent immediately cancels any in-flight child
     * sub-workflow runs without storing per-parent SET indexes that would grow
     * unbounded with reusable runs.
     */
    public void registerSubWorkflowParent(String childRunId, String parentRunId) {
        if (childRunId == null || parentRunId == null || childRunId.equals(parentRunId)) return;
        try {
            keyValueStore.set(PARENT_KEY_PREFIX + childRunId, parentRunId, PARENT_KEY_TTL);
            log.debug("[RedisPublisher] Registered sub-workflow parent: child={} → parent={}", childRunId, parentRunId);
        } catch (Exception e) {
            log.warn("[RedisPublisher] Failed to register sub-workflow parent (child={}, parent={}): {}",
                childRunId, parentRunId, e.getMessage());
        }
    }

    /**
     * F2.2 - remove the parent pointer when the sub-run is no longer relevant
     * (sub-workflow node returns or fails). Idempotent.
     */
    public void clearSubWorkflowParent(String childRunId) {
        if (childRunId == null) return;
        try {
            keyValueStore.delete(PARENT_KEY_PREFIX + childRunId);
        } catch (Exception e) {
            log.debug("[RedisPublisher] Failed to clear sub-workflow parent for child={}: {}",
                childRunId, e.getMessage());
        }
    }

    // ==================== Internal ====================

    private void publish(String channel, Map<String, Object> envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            eventBus.publish(channel, json);
            log.debug("[RedisPublisher] Published to channel {}: type={}", channel, envelope.get("type"));
        } catch (JsonProcessingException e) {
            log.warn("[RedisPublisher] Failed to serialize event for channel {}: {}", channel, e.getMessage());
        } catch (Exception e) {
            log.warn("[RedisPublisher] Failed to publish to channel {}: {} ({})", channel, e.getMessage(), e.getClass().getName(), e);
        }
    }
}
