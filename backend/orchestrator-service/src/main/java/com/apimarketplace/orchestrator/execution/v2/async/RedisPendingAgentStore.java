package com.apimarketplace.orchestrator.execution.v2.async;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Durable side store for {@link PendingAgent} entries, backed by Redis.
 *
 * <h2>Why this exists</h2>
 * <p>{@link PendingAgentRegistry} is intentionally in-memory only - it gives the
 * "happy path" zero-latency lookup when the worker delivers a result via pub/sub.
 * But a process restart wipes the registry and any in-flight agent loses its
 * orchestrator-side context, leaving the workflow run stuck in {@code RUNNING}.</p>
 *
 * <p>This store mirrors every {@code register} into a Redis key
 * ({@code agent:pending:{correlationId}}) so that on startup the
 * {@link AgentRecoveryService} can rebuild the registry, and the periodic scanner
 * can poll for results that arrived while the orchestrator was down.</p>
 *
 * <h2>TTL</h2>
 * <p>Each entry lives for a configurable TTL (default 2 hours) which must be
 * longer than the worker's result-key TTL (currently 1 hour) so the recovery
 * scanner has a buffer to find both the pending entry AND the result key.
 * Entries are deleted explicitly by {@link #remove(String)} on the happy path;
 * the TTL only protects against orchestrator crashes that leave the entry orphaned.</p>
 *
 * <h2>Activation</h2>
 * <p>Wired only when {@code scaling.agent.queue.enabled=true}. With the queue
 * disabled there are no async agents to track and the in-memory registry alone
 * is sufficient.</p>
 */
@Component
@ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")
public class RedisPendingAgentStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisPendingAgentStore.class);

    /** Key prefix - must NOT collide with the result key prefix in AgentQueueWorkerService. */
    static final String KEY_PREFIX = "agent:pending:";

    /**
     * Per-run reverse-index key. Each entry is a Redis SET of correlationIds belonging
     * to the run, used by {@link #hasAnyForRun(String)} so the recovery watchdog can
     * answer "is any async agent in flight for this run?" with O(1) cross-replica
     * accuracy - the in-memory {@link PendingAgentRegistry} only sees entries on the
     * replica that registered them.
     *
     * <p><b>Namespace MUST NOT be a prefix of {@link #KEY_PREFIX}.</b> {@link #listAll()}
     * uses {@code SCAN MATCH KEY_PREFIX + "*"} to find pending VALUEs (Redis STRINGs).
     * If the index namespace started with {@code "agent:pending:"} it would match this
     * SCAN and trigger {@code WRONGTYPE} on every recovery tick (the index is a SET, not
     * a STRING) - log churn + perf hit. Hyphen separator (vs colon) keeps the two
     * namespaces strictly disjoint at the SCAN level.</p>
     */
    static final String RUN_INDEX_PREFIX = "agent:pending-run-index:";

    /** Default TTL: longer than the worker result-key TTL (1h) to give recovery a buffer. */
    static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    /**
     * Production constructor - explicitly marked {@code @Autowired} because the class
     * also exposes a package-private test constructor. With multiple candidate
     * constructors Spring can't auto-detect which to use and falls back to looking
     * for a default constructor; annotating the real injection target forces the
     * correct choice.
     */
    @Autowired
    public RedisPendingAgentStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, DEFAULT_TTL);
    }

    /** Test constructor - allows overriding TTL for fast eviction tests. */
    RedisPendingAgentStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    /**
     * Mirror a {@link PendingAgent} into Redis with the configured TTL.
     * Called by {@link PendingAgentRegistry#register(PendingAgent)} when the store
     * is wired.
     */
    public void store(PendingAgent agent) {
        if (agent == null || agent.correlationId() == null) {
            return;
        }
        try {
            // ORDER MATTERS - index BEFORE value. The two-write sequence is not atomic
            // (no Lua script today), so a Redis crash between commands can leave the
            // store partially populated. We pick the failure mode deliberately:
            //   - Index without value (crash after SADD, before SET):
            //       hasAnyForRun → true (false-positive, BENIGN - watchdog skips a
            //       stranded entry until the 2 h TTL expires; AgentRecoveryService.scan
            //       will also surface the orphan and let it die).
            //   - Value without index (crash after SET, before SADD):
            //       hasAnyForRun → false (false-NEGATIVE on remote replicas - watchdog
            //       force-FAILs a live run, which is the EXACT bug class this whole
            //       fix is meant to prevent). UNACCEPTABLE.
            // Doing SADD+EXPIRE first guarantees that whenever the value is queryable
            // by the recovery scanner OR consumable via claim(), the run-level
            // hasAnyForRun lookup also returns true on every replica.
            if (agent.runId() != null) {
                String indexKey = RUN_INDEX_PREFIX + agent.runId();
                redisTemplate.opsForSet().add(indexKey, agent.correlationId());
                redisTemplate.expire(indexKey, ttl);
            }

            String json = objectMapper.writeValueAsString(toMap(agent));
            redisTemplate.opsForValue().set(
                KEY_PREFIX + agent.correlationId(), json, ttl.toMillis(), TimeUnit.MILLISECONDS);

            logger.debug("[RedisPendingAgentStore] Stored: correlationId={}, runId={}, nodeId={}",
                agent.correlationId(), agent.runId(), agent.nodeId());
        } catch (Exception e) {
            // Side-store failure must NOT break the in-memory registration. Log and move on -
            // worst case the recovery scanner won't see this entry and the run can be retried.
            logger.warn("[RedisPendingAgentStore] Failed to store pending agent: correlationId={}, error={}",
                agent.correlationId(), e.getMessage());
        }
    }

    /**
     * Atomically claim a pending entry: fetch its payload AND delete the Redis key in a
     * single operation (Redis {@code GETDEL}, Spring Data Redis
     * {@link org.springframework.data.redis.core.ValueOperations#getAndDelete(Object)}).
     *
     * <h2>Single-writer guarantee across replicas</h2>
     * <p>Redis {@code GETDEL} is atomic: only the caller that observes a non-null return
     * value actually removed the key. Every other replica racing for the same
     * {@code correlationId} observes {@code null}. That single winner is the only one
     * authorised to deliver the result. This turns the Redis side-store into the
     * cross-replica claim barrier without a separate lock key or Lua script.</p>
     *
     * <h2>Why return the full entry, not a boolean</h2>
     * <p>An earlier design returned {@code boolean} ("did we win?"), which left a stranding
     * bug: on a replica where the in-memory entry was lost (crash-recovered replica that
     * had not yet reached {@code registerFromRecovery}), Redis {@code DEL} would remove
     * the key AND the caller would return with no {@code PendingAgent} to drive delivery
     * - the run was then stranded, since the Redis key was gone and no other replica
     * could pick it up. Returning the full entry via {@code GETDEL} closes that gap:
     * whoever removes the key also gets the payload needed to deliver.</p>
     *
     * <h2>Failure semantics</h2>
     * <p>Redis errors are re-thrown so the caller can distinguish between "lost the race"
     * (empty Optional) and "Redis unavailable" (exception) and apply the appropriate
     * fallback. Parse errors after a successful claim are logged and return empty - the
     * key has already been removed so no retry is possible.</p>
     *
     * @return the claimed {@link PendingAgent} if this caller won the atomic GETDEL,
     *         or {@link Optional#empty()} if another caller already consumed the key
     *         (or if the stored payload failed to parse)
     * @throws RuntimeException if Redis is unreachable - caller must decide fallback
     */
    public Optional<PendingAgent> claim(String correlationId) {
        if (correlationId == null) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + correlationId);
        if (json == null) {
            logger.debug("[RedisPendingAgentStore] Claim lost (no key present): correlationId={}", correlationId);
            return Optional.empty();
        }
        try {
            PendingAgent agent = fromMap(objectMapper.readValue(json, new TypeReference<>() {}));
            // Drop the per-run reverse-index membership now that the entry is consumed.
            // Best-effort: if the SREM fails, the index stays oversized but `hasAnyForRun`
            // still returns true for the runId - eventual TTL convergence cleans it up.
            if (agent != null && agent.runId() != null) {
                try {
                    redisTemplate.opsForSet().remove(RUN_INDEX_PREFIX + agent.runId(), correlationId);
                } catch (Exception ignored) {
                    // Logged at debug only; this is not critical to the claim itself.
                    logger.debug("[RedisPendingAgentStore] Failed to drop run-index member: runId={}, correlationId={}",
                        agent.runId(), correlationId);
                }
            }
            logger.debug("[RedisPendingAgentStore] Claim won: correlationId={}, runId={}, nodeId={}",
                correlationId, agent != null ? agent.runId() : "?", agent != null ? agent.nodeId() : "?");
            return Optional.ofNullable(agent);
        } catch (Exception e) {
            // The key is gone but we cannot decode the payload. Nothing can be done -
            // the run will be picked up by the zombie scanner via hard-timeout fallback.
            logger.warn("[RedisPendingAgentStore] Claim won but payload parse failed: correlationId={}, error={}",
                correlationId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * True if at least one pending agent is registered in Redis for the given run.
     *
     * <p>Backed by the per-run reverse index ({@link #RUN_INDEX_PREFIX} + {@code runId})
     * maintained by {@link #store(PendingAgent)} and {@link #claim(String)}. This is
     * the cross-replica equivalent of {@link PendingAgentRegistry#hasAnyPendingForRun(String)}
     * (which only sees the local replica's heap). The recovery watchdog calls this
     * after the local check returns false to avoid false-positive zombie kills when
     * the registering replica is not the one running the watchdog scan.</p>
     *
     * <p>Failure semantics: a Redis exception is propagated to the caller so the
     * watchdog can apply its skip-on-error policy and avoid killing a live run on a
     * transient Redis hiccup. {@link Boolean#FALSE} only on a confirmed empty SET.</p>
     */
    public boolean hasAnyForRun(String runId) {
        if (runId == null) {
            return false;
        }
        Long size = redisTemplate.opsForSet().size(RUN_INDEX_PREFIX + runId);
        return size != null && size > 0;
    }

    /**
     * Cross-replica equivalent of
     * {@link PendingAgentRegistry#hasPendingFor(String, String, int)}: returns {@code true}
     * if Redis holds at least one PendingAgent for the given (runId, dagTriggerId, epoch)
     * tuple, regardless of which replica registered it.
     *
     * <p>Why this exists: the in-memory {@code hasPendingFor} only sees entries on the
     * current JVM. When orchestrator runs with horizontal scaling
     * ({@code scaling.agent.queue.enabled=true}), the cron dispatcher and the agent
     * dispatcher can land on different replicas - replica B may try to close an epoch
     * (via {@code resetForNextCycle}) while the 5 PendingAgents live only on replica A.
     * Without this fallback, the close proceeds and successor traversal becomes impossible
     * in the now-pruned epoch (Gmail Auto-Labeler regression family).</p>
     *
     * <p>Implementation: read the per-run index ({@link #RUN_INDEX_PREFIX}) for the
     * candidate correlation ids, then GET each value and filter by triggerId + epoch.
     * O(N) where N is the number of entries for the run - typically < 30 in practice.
     * The cost is bounded and only incurred when the local map says "no pending".</p>
     *
     * <p>Failure semantics: any Redis exception falls back to {@code false} (allow the
     * caller's reset to proceed). This is the same trade-off as the local check -
     * over-permissive on Redis hiccup, never blocks epoch closure on a transient outage.</p>
     */
    public boolean hasPendingFor(String runId, String dagTriggerId, int epoch) {
        if (runId == null || dagTriggerId == null) {
            return false;
        }
        try {
            java.util.Set<String> correlationIds =
                redisTemplate.opsForSet().members(RUN_INDEX_PREFIX + runId);
            if (correlationIds == null || correlationIds.isEmpty()) {
                return false;
            }
            for (String correlationId : correlationIds) {
                try {
                    String json = redisTemplate.opsForValue().get(KEY_PREFIX + correlationId);
                    if (json == null) {
                        continue; // Race: TTL expired between SMEMBERS and GET
                    }
                    PendingAgent agent = fromMap(
                        objectMapper.readValue(json, new TypeReference<>() {}));
                    if (agent != null
                            && dagTriggerId.equals(agent.dagTriggerId())
                            && agent.epoch() == epoch) {
                        return true;
                    }
                } catch (Exception e) {
                    // Single-entry parse failure must not derail the scan
                    logger.warn("[RedisPendingAgentStore] hasPendingFor entry parse failed: "
                            + "runId={}, correlationId={}, error={}",
                        runId, correlationId, e.getMessage());
                }
            }
            return false;
        } catch (Exception e) {
            logger.warn("[RedisPendingAgentStore] hasPendingFor failed: runId={}, "
                    + "triggerId={}, epoch={}, error={}",
                runId, dagTriggerId, epoch, e.getMessage());
            return false;
        }
    }

    /**
     * Re-asserts the per-run reverse-index membership for an entry being recovered from
     * the side-store at startup. Idempotent (SADD is set semantics) and cheap.
     *
     * <p>Closes a recovery-time gap: if the original {@link #store(PendingAgent)} call
     * crashed AFTER the value SET but BEFORE the SADD (the brief window the new index-first
     * order in {@code store} eliminates going forward but historical orphans may still
     * exist), the value is found by {@code listAll} on startup but the index is missing.
     * Without this re-assertion, {@link #hasAnyForRun(String)} returns false on every
     * replica and the watchdog force-FAILs the recovered run - exactly the bug class
     * the cross-replica index was added to prevent.</p>
     *
     * <p>Errors are swallowed (logged at WARN) - recovery already restored the in-memory
     * registry, so a transient Redis hiccup at this point degrades to "single-replica
     * visibility" rather than blocking startup.</p>
     */
    public void touchIndex(PendingAgent agent) {
        if (agent == null || agent.runId() == null || agent.correlationId() == null) {
            return;
        }
        try {
            String indexKey = RUN_INDEX_PREFIX + agent.runId();
            redisTemplate.opsForSet().add(indexKey, agent.correlationId());
            redisTemplate.expire(indexKey, ttl);
        } catch (Exception e) {
            logger.warn("[RedisPendingAgentStore] touchIndex failed: runId={}, correlationId={}, error={}",
                agent.runId(), agent.correlationId(), e.getMessage());
        }
    }

    /**
     * Scan Redis for all live pending entries. Used by {@link AgentRecoveryService}
     * on startup and during periodic scans. Uses SCAN (not KEYS) to avoid blocking
     * the Redis server.
     *
     * <p>The scan runs inside {@code redisTemplate.execute(RedisCallback)} so Spring
     * manages the underlying connection lifecycle - a previous implementation pulled
     * the connection directly via {@code getConnectionFactory().getConnection()} and
     * only closed the {@link Cursor}, leaking one connection per scan invocation
     * (every startup + every 30s periodic tick).</p>
     */
    public List<PendingAgent> listAll() {
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build();
        try {
            List<PendingAgent> result = redisTemplate.execute((RedisCallback<List<PendingAgent>>) connection -> {
                List<PendingAgent> agents = new ArrayList<>();
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        String key = new String(cursor.next());
                        try {
                            String json = redisTemplate.opsForValue().get(key);
                            if (json == null) {
                                continue; // Race: TTL expired between SCAN and GET
                            }
                            PendingAgent agent = fromMap(objectMapper.readValue(json, new TypeReference<>() {}));
                            if (agent != null) {
                                agents.add(agent);
                            }
                        } catch (Exception e) {
                            logger.warn("[RedisPendingAgentStore] Failed to parse entry: key={}, error={}",
                                key, e.getMessage());
                        }
                    }
                }
                return agents;
            });
            return result != null ? result : List.of();
        } catch (Exception e) {
            logger.error("[RedisPendingAgentStore] SCAN failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Serialization helpers - record → Map → JSON to avoid Jackson record-discovery
    // edge cases and to keep splitItemData round-trippable as a plain Map.
    // ═══════════════════════════════════════════════════════════════════════════

    private static Map<String, Object> toMap(PendingAgent agent) {
        Map<String, Object> m = new HashMap<>();
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
        m.put("loopIteration", agent.loopIteration());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static PendingAgent fromMap(Map<String, Object> m) {
        if (m == null) return null;
        Object startedAtObj = m.get("startedAtEpochMs");
        Instant startedAt = startedAtObj instanceof Number n
            ? Instant.ofEpochMilli(n.longValue())
            : Instant.now();
        Object epochObj = m.get("epoch");
        Object itemIndexObj = m.get("itemIndex");
        return new PendingAgent(
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
            (String) m.get("organizationId"),
            m.get("loopIteration") instanceof Number ln ? ln.intValue() : null
        );
    }
}
