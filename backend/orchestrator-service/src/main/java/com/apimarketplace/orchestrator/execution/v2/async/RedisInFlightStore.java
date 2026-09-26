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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>30 min. Long enough to outlive a k8s rolling restart + Flyway boot; short enough that
 * a permanently-stale entry (clear failed AND the catch-path re-registration also failed
 * AND recovery never fired) doesn't accumulate in Redis indefinitely. Deliberately NOT
 * raised alongside the 130-min {@code scaling.agent.recovery.hard-timeout-ms}: an entry is
 * staged AFTER the result has already arrived, so the run's duration is irrelevant here -
 * only the restart-to-replay window matters.
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

    /** Restart-to-replay window only (see class javadoc) - independent of the recovery hard timeout. */
    static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    /**
     * Correlation ids THIS instance staged and has not cleared yet, i.e. deliveries that are
     * running on this JVM right now. The Redis keyspace is shared by every replica (and keeps
     * the entries a crashed replica left behind until they are replayed or expire), so
     * {@link #size()} answers a cluster question; the shutdown drain needs the local one,
     * because it can only wait for work this instance will actually finish.
     */
    private final Set<String> locallyStaged = ConcurrentHashMap.newKeySet();

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
        // Tracked locally BEFORE any Redis I/O and whatever that I/O returns: the delivery is
        // running on this instance from here until clear(), even when Redis refuses the stage.
        if (pending.correlationId() != null) {
            locallyStaged.add(pending.correlationId());
        }
        try {
            String json = objectMapper.writeValueAsString(toInFlightMap(pending, result));
            // Value + index member + index TTL in ONE Redis transaction, so no observer can
            // ever see the member without its value.
            //
            // This used to be an ordered pair of round trips (SADD, then SET), and the gap
            // between them was a real defect rather than a theoretical one. A per-run guard
            // reading in that gap resolved the member's value to null and, per the old
            // listForRun, PRUNED the member. Nothing re-added it - the SADD had already
            // happened - so from that moment the delivery was invisible to every per-run
            // guard, its epoch could be closed while it was still being delivered, and the
            // node downstream of the agent was never dispatched, never marked SKIPPED, and
            // got no step row. Nothing above INFO was logged. In CI (cpu: 500m) that window,
            // three Redis round trips wide, is wide enough to land in; on a developer machine
            // it is not, which is why the affected e2e classes never reproduced locally.
            //
            // A transaction is available here because Redis is standalone in every deployment
            // of this repo (plain spring.data.redis host/port/database, no cluster block, a
            // single redis:7-alpine in both compose files, one host:port in values-prod). On a
            // clustered Redis these three keys would be cross-slot and MULTI would be refused.
            boolean indexed = stageAtomically(pending.runId(), pending.correlationId(), json);
            if (!indexed) {
                // The value IS written even so: AgentRecoveryService.replayInFlightEntries scans
                // the value namespace directly, so it still recovers this delivery after a crash.
                // What is lost is the per-run guards - they read the index, so this agent is
                // invisible to them and its epoch can be reset mid-delivery. That is precisely
                // the bug the index exists to prevent, so it is reported at ERROR rather than
                // swallowed: a silent WARN here would make this class quietly stop protecting
                // the very thing it was added for.
                logger.error("[InFlightStore] STAGED WITHOUT INDEX for correlationId={} runId={} - "
                        + "the per-run in-flight guards cannot see this agent, so its epoch may be "
                        + "reset while the delivery is still running",
                    pending.correlationId(), pending.runId());
                // Write the value on its own: AgentRecoveryService.replayInFlightEntries scans
                // the value namespace directly and can still recover this delivery after a
                // crash, which is worth preserving even when the guards are lost.
                //
                // Idempotent on purpose. Usually nothing was written (a discarded transaction
                // applies nothing), but one case writes twice: an EXEC that reached the server
                // and applied, whose reply then failed client-side. The re-SET is the same JSON
                // over the same key, so the only cost is a redundant round trip - and in that
                // case the ERROR above is a false alarm, which is the price of not being able
                // to tell the two apart from here.
                redisTemplate.opsForValue().set(KEY_PREFIX + pending.correlationId(), json,
                    ttl.toMillis(), TimeUnit.MILLISECONDS);
            }
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
        deleteStagedEntry(correlationId);
        // Released only here, i.e. once the WHOLE delivery (epoch close included) is over:
        // the shutdown drain reads localSize() and must not see this delivery as finished
        // while it is still writing.
        locallyStaged.remove(correlationId);
        // The per-run index member is deliberately NOT removed here. Deleting the value is what
        // makes the entry stop counting; the leftover member is pruned lazily by
        // {@link #listForRun} the next time anyone looks, and expires with the index TTL
        // regardless. Keeping clear() single-argument means every existing call site (and the
        // recovery paths that hold no runId) stays correct without threading one through.
    }

    /**
     * Delete the Redis entry only, leaving this instance's local count untouched. For a
     * delivery that must disappear from the per-run guards EARLY (so a sibling's "anyone still
     * in flight?" check does not see it) while it is still running on this instance; the
     * delivery's final {@link #clear} releases the local count.
     */
    public void deleteStagedEntry(String correlationId) {
        if (correlationId == null || correlationId.isEmpty()) return;
        try {
            redisTemplate.delete(KEY_PREFIX + correlationId);
        } catch (Exception e) {
            logger.warn("[InFlightStore] clear failed for correlationId={}: {}", correlationId, e.getMessage());
        }
    }

    /** Per-run reverse index, so the guards below never scan the keyspace. */
    static final String RUN_INDEX_PREFIX = "agent:in-flight-run-index:";

    /**
     * Write the value, the index member and the index TTL as ONE Redis transaction.
     *
     * <p>Atomicity is the point, not a nicety. As three separate round trips there was an
     * interval in which the member existed and its value did not, and a per-run guard reading
     * in it saw an unresolvable member - which {@link #listForRun} is entitled to prune,
     * because after this method returns an unresolvable member really is stale. MULTI removes
     * the interval instead of teaching every reader to tolerate it.
     *
     * <p>All-or-nothing: on failure the caller writes the value alone and logs the loss of
     * guard protection, which is strictly better than a half-applied index.
     *
     * @return true when the transaction applied and the entry is therefore visible to the
     *         per-run guards. One retry, because the caller cannot come back later: by the
     *         time the guards look, the delivery is already running and an unindexed entry
     *         has already lost its protection.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean stageAtomically(String runId, String correlationId, String json) {
        if (runId == null || correlationId == null) return false;
        String indexKey = RUN_INDEX_PREFIX + runId;
        String valueKey = KEY_PREFIX + correlationId;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                List<Object> applied = redisTemplate.execute(new org.springframework.data.redis.core.SessionCallback<List<Object>>() {
                    @Override
                    public List<Object> execute(org.springframework.data.redis.core.RedisOperations operations) {
                        operations.multi();
                        operations.opsForValue().set(valueKey, json, ttl.toMillis(), TimeUnit.MILLISECONDS);
                        operations.opsForSet().add(indexKey, correlationId);
                        // Same TTL as the entries it points at, so an index abandoned by a crash
                        // cannot outlive them and keep a run's resets deferred forever.
                        operations.expire(indexKey, ttl.toMillis(), TimeUnit.MILLISECONDS);
                        return operations.exec();
                    }
                });
                // A null result is Spring's signal that the transaction was DISCARDED (a
                // queue-time error, or a WATCH conflict). Treating that as success would
                // report protection the guards do not have.
                //
                // Deliberately NOT checking the result arity: the number of wire commands is
                // Spring's business, not this method's. RedisTemplate.expire, for one, may
                // decompose into PEXPIRE with an EXPIRE fallback depending on the driver, so
                // pinning "exactly 3" would couple this guard to an implementation detail and
                // turn a harmless refactor into a permanent, and false, STAGED WITHOUT INDEX
                // error on every single agent delivery.
                if (applied != null) {
                    return true;
                }
                logger.warn("[InFlightStore] stage transaction was discarded (attempt {}/2) for correlationId={} runId={}",
                    attempt, correlationId, runId);
            } catch (Exception e) {
                logger.warn("[InFlightStore] index add attempt {}/2 failed for correlationId={} runId={}: {}",
                    attempt, correlationId, runId, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Staged entries for ONE run, read through the per-run index.
     *
     * <p>Members whose value no longer resolves (TTL expiry, or a delete whose index cleanup
     * failed) are skipped AND pruned, so a stale member can never keep a run's epoch from
     * resetting.
     *
     * <p><b>The prune depends on {@link #stage} being atomic</b>, and the two must stay
     * coupled. An unresolvable member means "stale" only if no writer can be halfway through
     * creating it. When staging was a SADD followed by a separate SET, a guard reading between
     * the two pruned a member that was about to become valid, and nothing re-added it - the
     * delivery went permanently invisible to every per-run guard and its epoch could be closed
     * mid-delivery. If anyone ever splits {@code stage} back into separate round trips, this
     * prune becomes that defect again.
     *
     * <p>Necessary, not sufficient: the prune is itself a check-then-act across two round
     * trips (GET, then SREM), so an atomic re-stage of the SAME correlationId landing between
     * them still loses a member whose value now exists. That is reachable - a delivery that
     * throws clears the value while deliberately leaving the member, and the recovery scan
     * re-stages the same id. The window is one round trip rather than the three-round-trip one
     * removed from {@code stage}, so this is a narrowing and not a closure. Conditioning the
     * SREM on the value still being absent (one Lua script) is what would close it.
     *
     * <p>Fails OPEN on a Redis error - an empty list restores the pre-index behaviour
     * rather than freezing the cycle, which is the same policy {@link #listAll} already uses.
     */
    List<InFlightEntry> listForRun(String runId) {
        List<InFlightEntry> entries = new ArrayList<>();
        if (runId == null) return entries;
        java.util.Set<String> members;
        try {
            members = redisTemplate.opsForSet().members(RUN_INDEX_PREFIX + runId);
        } catch (Exception e) {
            logger.warn("[InFlightStore] index read failed for runId={}: {}", runId, e.getMessage());
            return entries;
        }
        if (members == null || members.isEmpty()) return entries;
        for (String correlationId : members) {
            try {
                String json = redisTemplate.opsForValue().get(KEY_PREFIX + correlationId);
                if (json == null) {
                    redisTemplate.opsForSet().remove(RUN_INDEX_PREFIX + runId, correlationId);
                    continue;
                }
                Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
                InFlightEntry entry = fromInFlightMap(map);
                if (entry != null) entries.add(entry);
            } catch (Exception e) {
                logger.warn("[InFlightStore] failed to resolve in-flight {}: {}", correlationId, e.getMessage());
            }
        }
        return entries;
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

    /**
     * Number of staged entries across the WHOLE cluster (every replica shares this keyspace),
     * including entries a crashed replica left for the next startup replay. Observability only:
     * do not wait on it for this instance's shutdown, use {@link #localSize()}.
     */
    public int size() {
        return listAll().size();
    }

    /**
     * Number of deliveries this instance staged and has not cleared yet. No Redis call. This is
     * what a shutdown drain can wait for: every other entry belongs to another live replica, or
     * to a dead one and is replayed by the next startup recovery.
     */
    public int localSize() {
        return locallyStaged.size();
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
     * <p>Bounded cost: read through the per-run index ({@link #RUN_INDEX_PREFIX}), so this costs
     * one {@code SMEMBERS} plus one {@code GET} per agent currently mid-delivery FOR THIS RUN.
     * It deliberately does not use {@link #listAll()}: that runs {@code SCAN} over the whole
     * keyspace, whose cost is a function of the keyspace rather than of the match set, and this
     * predicate is now on the epoch-close path of every reusable trigger - including workflows
     * with no agents at all - inside a transaction holding an advisory lock and a row lock.
     */
    public boolean hasOtherInFlightForEpoch(String runId, String dagTriggerId, int epoch,
                                            String excludeCorrelationId) {
        if (runId == null) {
            return false;
        }
        for (InFlightEntry entry : listForRun(runId)) {
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

    /**
     * True when ANY staged in-flight entry exists for the run, whatever its trigger
     * or epoch.
     *
     * <p>The run-wide counterpart of {@link #hasOtherInFlightForEpoch}, for callers
     * that hold no epoch to scope by - the legacy {@code resetForNextCycle} overload
     * that passes {@code triggerId=null} is the one that matters. It mirrors
     * {@link PendingAgentRegistry#hasAnyPendingForRun} and answers the same question
     * for the window that registry cannot see: between {@code consume()} and the end
     * of delivery.
     */
    public boolean hasAnyInFlightForRun(String runId) {
        return !listForRun(runId).isEmpty();
    }

    /** Key prefix for the startup-replay claim markers ({@link #tryClaimReplay}). */
    static final String REPLAY_CLAIM_PREFIX = "agent:in_flight_replay:";

    /**
     * TTL for replay-claim markers. Long enough to cover a full replay pass; short
     * enough that a pod crashing mid-replay only delays the retry by ~2 min (the
     * staged {@code agent:in_flight:*} entry survives, so no work is lost).
     */
    static final Duration REPLAY_CLAIM_TTL = Duration.ofSeconds(120);

    /**
     * Cross-pod claim barrier for the startup/scan in-flight replay. Two replicas
     * booting near-simultaneously (rolling restart) both enumerate the same staged
     * entries; without a claim both replay, and non-idempotent side-effecting
     * successors (send_email, http_request) can double-fire. SETNX-with-TTL makes
     * exactly one pod win per correlationId.
     *
     * <p><b>Failure mode: fail-open.</b> A Redis error returns {@code true} (proceed
     * with the replay) - preserving at-least-once delivery matters more than the
     * narrow duplicate window that only exists while Redis is erroring.
     */
    public boolean tryClaimReplay(String correlationId) {
        if (correlationId == null || correlationId.isEmpty()) return false;
        try {
            Boolean won = redisTemplate.opsForValue().setIfAbsent(
                REPLAY_CLAIM_PREFIX + correlationId, "1",
                REPLAY_CLAIM_TTL.toMillis(), TimeUnit.MILLISECONDS);
            return !Boolean.FALSE.equals(won);
        } catch (Exception e) {
            logger.warn("[InFlightStore] tryClaimReplay failed for correlationId={} - proceeding (fail-open): {}",
                correlationId, e.getMessage());
            return true;
        }
    }

    /**
     * Release a replay claim after a FAILED replay. Best-effort: on most throw paths
     * the delivery pipeline's own finally has already cleared the staged entry, so
     * this only matters for the narrow pre-delivery throws where the entry survives -
     * holding the claim there would delay that retry by {@link #REPLAY_CLAIM_TTL}.
     */
    public void releaseReplayClaim(String correlationId) {
        if (correlationId == null || correlationId.isEmpty()) return;
        try {
            redisTemplate.delete(REPLAY_CLAIM_PREFIX + correlationId);
        } catch (Exception e) {
            logger.warn("[InFlightStore] releaseReplayClaim failed for correlationId={}: {}",
                correlationId, e.getMessage());
        }
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
