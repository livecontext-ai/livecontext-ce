package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Durable in-flight ack store for the window between
 * {@link PendingAgentRegistry#consume(String)} (which atomically GETDELs the side-store entry
 * for cross-replica dedup) and {@link com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator#complete}
 * (which actually persists the step row and emits successor edges).
 *
 * <h2>Why this exists</h2>
 *
 * <p>Prod incident 2026-05-22 21:01 UTC: orchestrator JVM hit OOM mid-flight.
 * {@code AgentAsyncCompletionService.onResult} had called {@code registry.consume(cid)} for
 * 10 classify results (atomically removing them from {@link RedisPendingAgentStore}) but
 * the downstream {@code stepCompletionOrchestrator.complete(ctx, …)} had not finished
 * persisting. After restart, {@link AgentRecoveryService#recoverOnStartup} found 0 pending
 * entries (the GETDEL had already happened pre-crash) → no replay → 5 min later the zombie
 * scanner flipped the run to FAILED.
 *
 * <p>This store closes that gap: immediately AFTER {@code consume} wins the Redis GETDEL,
 * the consumed {@code PendingAgent} and inbound {@code AgentResultMessage} are staged here
 * under {@code agent:in_flight:{correlationId}}. AFTER {@code complete} returns (success
 * path OR catch path that re-registers the pending), the entry is cleared. A crash between
 * {@code stage} and {@code clear} leaves a durable replay record that
 * {@link AgentRecoveryService#recoverOnStartup} discovers via {@link #listAll()} and
 * delivers through {@code replayInFlightResult} - idempotency is guaranteed by the
 * {@code idx_workflow_step_data_unique_v6} unique constraint at the persistence layer.
 *
 * <h2>TTL</h2>
 *
 * <p>30 min (matches {@code scaling.agent.recovery.hard-timeout-ms} default). Long enough
 * to outlive a k8s rolling restart + Flyway boot; short enough that a permanently-stale
 * entry (clear failed AND the catch-path re-registration also failed AND recovery never
 * fired) doesn't accumulate in Redis indefinitely.
 *
 * <h2>Activation</h2>
 *
 * <p>Wired only when {@code scaling.agent.queue.enabled=true} (same condition as
 * {@link RedisPendingAgentStore}). With the queue disabled there is no async ack to protect.
 */
@Component
@ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")
public class RedisInFlightStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisInFlightStore.class);

    /** Key prefix. Distinct from {@link RedisPendingAgentStore#KEY_PREFIX} ({@code "agent:pending:"}). */
    static final String KEY_PREFIX = "agent:in_flight:";

    /** TTL aligned with {@code scaling.agent.recovery.hard-timeout-ms} (30 min default). */
    static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    // @Autowired marks this as THE Spring-injection constructor - without it,
    // Spring sees TWO constructors (primary + test-only TTL override) and falls
    // back to looking for a no-arg constructor → startup crash "No default
    // constructor found" (incident 2026-05-22 - same shape as the cycle that
    // broke prod orchestrator).
    @org.springframework.beans.factory.annotation.Autowired
    public RedisInFlightStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, DEFAULT_TTL);
    }

    /** Test-only - override TTL for fast expiry assertions. */
    RedisInFlightStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    /**
     * Stage a {@code (pending, result)} pair under {@code agent:in_flight:{correlationId}}.
     * MUST be called immediately after {@link PendingAgentRegistry#consume} succeeds, using
     * the consumed payload as source of truth. Idempotent: re-staging the same correlationId
     * overwrites the previous value (which is the correct behavior - the freshest delivery wins).
     */
    public void stage(PendingAgent pending, AgentResultMessage result) {
        if (pending == null || result == null) return;
        try {
            String json = objectMapper.writeValueAsString(toInFlightMap(pending, result));
            redisTemplate.opsForValue().set(KEY_PREFIX + pending.correlationId(), json, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Best-effort: a Redis hiccup on staging degrades to the prior (unprotected)
            // behavior. We log loud but do NOT throw - the worker result is already in
            // the orchestrator's hand, refusing to ack it would lose work for sure.
            logger.warn("[InFlightStore] stage failed for correlationId={} runId={}: {}",
                pending.correlationId(), pending.runId(), e.getMessage());
        }
    }

    /**
     * Clear the staged entry. Called unconditionally in a {@code finally} block around
     * {@code deliverUnderLock} - covers both the happy path (complete returned cleanly)
     * AND the catch path (re-registered the pending into the queue for retry). On the
     * catch path the unconditional clear is intentional: the standard recovery pipeline
     * will re-deliver the result via {@code agent:pending:*} on the next scan tick;
     * leaving an in_flight twin would cause double-delivery the next time
     * {@code AgentRecoveryService.replayInFlightEntries} fires.
     */
    public void clear(String correlationId) {
        if (correlationId == null || correlationId.isEmpty()) return;
        try {
            redisTemplate.delete(KEY_PREFIX + correlationId);
        } catch (Exception e) {
            logger.warn("[InFlightStore] clear failed for correlationId={}: {}", correlationId, e.getMessage());
        }
    }

    /**
     * Enumerate all staged entries via {@code SCAN MATCH agent:in_flight:*}. Used by
     * {@link AgentRecoveryService#recoverOnStartup} to replay any in-flight ack that
     * was orphaned by a JVM crash.
     */
    public List<InFlightEntry> listAll() {
        List<InFlightEntry> entries = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build();
        try (Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(
                conn -> conn.scan(options))) {
            if (cursor == null) return entries;
            while (cursor.hasNext()) {
                String key = new String(cursor.next());
                try {
                    String json = redisTemplate.opsForValue().get(key);
                    if (json == null) continue;
                    Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
                    InFlightEntry entry = fromInFlightMap(map);
                    if (entry != null) entries.add(entry);
                } catch (Exception e) {
                    logger.warn("[InFlightStore] failed to parse {}: {}", key, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("[InFlightStore] listAll failed: {}", e.getMessage());
        }
        return entries;
    }

    /** Counter for ops/test observability - number of currently-staged entries. */
    public int size() {
        return listAll().size();
    }

    /**
     * True when at least one staged in-flight entry exists for the given
     * {@code (runId, dagTriggerId, epoch)} whose correlationId is NOT
     * {@code excludeCorrelationId}.
     *
     * <p><b>Why the async drain check needs this.</b> {@link PendingAgentRegistry#consume}
     * GETDELs a completion's pending entry at the very start of {@code onAgentResult} - so by
     * the time the FIRST of N parallel fork-branch agents reaches its post-delivery drain check,
     * its siblings' results may already have been consumed (registry-empty) while their
     * {@code deliverUnderLock} is still queued behind the per-run lock. Closing the epoch on that
     * false "registry drained" signal prunes the epoch state the siblings (and the downstream
     * merge) still need, so the merge never fires. Those consumed-but-not-yet-delivered siblings
     * ARE recorded here (staged at consume, cleared only after delivery), so this lets the drain
     * check see them and defer the reset until the genuinely-last delivery. Cross-replica safe:
     * the store is Redis-backed, unlike the per-JVM {@code runLockStripes}.
     *
     * <p>Bounded cost: the store only ever holds agents mid-delivery (cleared in a finally after
     * each delivery), so the {@link #listAll()} SCAN it runs is over a small key set.
     */
    public boolean hasOtherInFlightForEpoch(String runId, String dagTriggerId, int epoch,
                                            String excludeCorrelationId) {
        if (runId == null) {
            return false;
        }
        for (InFlightEntry entry : listAll()) {
            PendingAgent p = entry.pending();
            if (p == null) {
                continue;
            }
            if (!runId.equals(p.runId())) {
                continue;
            }
            if (dagTriggerId != null && !dagTriggerId.equals(p.dagTriggerId())) {
                continue;
            }
            if (p.epoch() != epoch) {
                continue;
            }
            if (excludeCorrelationId != null && excludeCorrelationId.equals(p.correlationId())) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Paired (pending, result) entry recovered from the in-flight store. */
    public record InFlightEntry(PendingAgent pending, AgentResultMessage result) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // Serialization helpers - duplicate of RedisPendingAgentStore.toMap/fromMap
    // for the PendingAgent fields, plus the AgentResultMessage payload wrapped
    // under "result*" keys. Keeping a separate copy here so the two stores are
    // independently evolvable (in-flight schema can change without bumping the
    // pending-store schema).
    // ═══════════════════════════════════════════════════════════════════════════

    static Map<String, Object> toInFlightMap(PendingAgent agent, AgentResultMessage result) {
        Map<String, Object> m = new HashMap<>();
        // PendingAgent fields
        m.put("correlationId", agent.correlationId());
        m.put("runId", agent.runId());
        m.put("nodeId", agent.nodeId());
        m.put("nodeLabel", agent.nodeLabel());
        m.put("dagTriggerId", agent.dagTriggerId());
        m.put("epoch", agent.epoch());
        m.put("itemIndex", agent.itemIndex());
        m.put("itemId", agent.itemId());
        m.put("agentType", agent.agentType());
        m.put("tenantId", agent.tenantId());
        m.put("splitItemData", agent.splitItemData());
        m.put("resolvedInputData", agent.resolvedInputData());
        m.put("conversationId", agent.conversationId());
        m.put("streamId", agent.streamId());
        m.put("executionId", agent.executionId());
        m.put("model", agent.model());
        m.put("resolvedSystemPrompt", agent.resolvedSystemPrompt());
        m.put("resolvedUserPrompt", agent.resolvedUserPrompt());
        m.put("startedAtEpochMs", agent.startedAt() != null ? agent.startedAt().toEpochMilli() : null);
        m.put("organizationId", agent.organizationId());
        // AgentResultMessage payload
        m.put("resultPayload", result.result());
        m.put("resultSuccess", result.success());
        m.put("resultErrorMessage", result.errorMessage());
        m.put("resultAgentType", result.agentType());
        m.put("resultCompletedAtEpochMs", result.completedAt() != null ? result.completedAt().toEpochMilli() : null);
        return m;
    }

    @SuppressWarnings("unchecked")
    static InFlightEntry fromInFlightMap(Map<String, Object> m) {
        if (m == null) return null;
        Object startedAtObj = m.get("startedAtEpochMs");
        Instant startedAt = startedAtObj instanceof Number n
            ? Instant.ofEpochMilli(n.longValue())
            : Instant.now();
        Object epochObj = m.get("epoch");
        Object itemIndexObj = m.get("itemIndex");
        PendingAgent pending = new PendingAgent(
            (String) m.get("correlationId"),
            (String) m.get("runId"),
            (String) m.get("nodeId"),
            (String) m.get("nodeLabel"),
            (String) m.get("dagTriggerId"),
            epochObj instanceof Number en ? en.intValue() : 0,
            itemIndexObj instanceof Number in ? in.intValue() : 0,
            (String) m.get("itemId"),
            (String) m.get("agentType"),
            (String) m.get("tenantId"),
            (Map<String, Object>) m.get("splitItemData"),
            (Map<String, Object>) m.get("resolvedInputData"),
            (String) m.get("conversationId"),
            (String) m.get("streamId"),
            (String) m.get("executionId"),
            (String) m.get("model"),
            (String) m.get("resolvedSystemPrompt"),
            (String) m.get("resolvedUserPrompt"),
            startedAt,
            (String) m.get("organizationId")
        );
        Object completedAtObj = m.get("resultCompletedAtEpochMs");
        Instant completedAt = completedAtObj instanceof Number cn
            ? Instant.ofEpochMilli(cn.longValue())
            : Instant.now();
        Boolean success = m.get("resultSuccess") instanceof Boolean b ? b : Boolean.FALSE;
        AgentResultMessage result = new AgentResultMessage(
            (String) m.get("correlationId"),
            (String) m.get("runId"),
            (String) m.get("nodeId"),
            (Map<String, Object>) m.get("resultPayload"),
            success,
            (String) m.get("resultErrorMessage"),
            (String) m.get("resultAgentType"),
            completedAt
        );
        return new InFlightEntry(pending, result);
    }
}
