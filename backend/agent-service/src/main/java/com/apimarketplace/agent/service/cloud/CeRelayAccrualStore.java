package com.apimarketplace.agent.service.cloud;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Per-execution running token accrual for centralized CE LLM relay billing.
 *
 * <p>When a CE install relays an execution with a correlation {@code executionId}, the cloud
 * relay does NOT bill each forwarded call; it accrues the usage here instead, and settles ONE
 * {@code CE_LLM_RELAY} ledger line at the end (or via the reaper if the install crashes before
 * sending the terminal settle). This store serves two readers:
 * <ol>
 *   <li>the per-call budget gate, which needs the cumulative tokens-so-far to check
 *       {@code balance ≥ cost(accrued + nextCall)};</li>
 *   <li>the crash-recovery reaper, which lists abandoned executions ({@link #findStale}) and
 *       settles them from the persisted accrual.</li>
 * </ol>
 *
 * <p><b>Backed by Redis</b> (a hash per execution + a sorted-set index scored by last-update
 * time). {@code HINCRBY} makes the accrual atomic under concurrent turns. The key is NOT given a
 * short TTL - a short expiry would silently drop the accrual of a crashed execution before the
 * reaper could bill it (lost revenue). A long safety TTL ({@link #SAFETY_TTL_HOURS}h) is the only
 * ultimate GC for keys the reaper never reaches; the reaper itself removes keys after settling.
 * CE's terminal settle carries the authoritative aggregate, so this store is the reaper's safety
 * net, not the source of truth on the happy path.
 */
@Component
public class CeRelayAccrualStore {

    static final String KEY_PREFIX = "ce-relay:accrual:";
    static final String INDEX_KEY = "ce-relay:accrual:index";
    private static final long SAFETY_TTL_HOURS = 168; // 7 days - ultimate GC, far beyond the reaper window

    private static final String F_USER = "userId";
    private static final String F_PROVIDER = "provider";
    private static final String F_MODEL = "model";
    private static final String F_PROMPT = "promptTokens";
    private static final String F_COMPLETION = "completionTokens";
    private static final String F_CACHE_CREATION = "cacheCreationTokens";
    private static final String F_CACHE_READ = "cacheReadTokens";
    private static final String F_CACHED = "cachedTokens";
    private static final String F_REASONING = "reasoningTokens";
    private static final String F_UPDATED_AT = "updatedAt";

    private final StringRedisTemplate redis;

    public CeRelayAccrualStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Token deltas reported by one relayed completion (or the running totals in a snapshot). */
    public record AccruedUsage(long promptTokens, long completionTokens,
                               long cacheCreationTokens, long cacheReadTokens,
                               long cachedTokens, long reasoningTokens) {
        public static final AccruedUsage ZERO = new AccruedUsage(0, 0, 0, 0, 0, 0);
    }

    /** Full accrual state for one execution. */
    public record AccruedSnapshot(String userId, String provider, String model,
                                  AccruedUsage usage, long updatedAtEpochMs) {}

    /**
     * Add one completion's usage to the execution's running total (atomic per field), stamp the
     * metadata + last-update time, and index the execution for reaper discovery. Idempotent on
     * metadata; additive on tokens.
     */
    public void accrue(String executionId, String userId, String provider, String model,
                       AccruedUsage delta, long nowEpochMs) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        String key = KEY_PREFIX + executionId;
        var hash = redis.opsForHash();
        // Metadata: set every call (cheap, idempotent) so the reaper can settle without CE.
        if (userId != null) hash.put(key, F_USER, userId);
        if (provider != null) hash.put(key, F_PROVIDER, provider);
        if (model != null) hash.put(key, F_MODEL, model);
        // Tokens: atomic additive accrual.
        hash.increment(key, F_PROMPT, delta.promptTokens());
        hash.increment(key, F_COMPLETION, delta.completionTokens());
        hash.increment(key, F_CACHE_CREATION, delta.cacheCreationTokens());
        hash.increment(key, F_CACHE_READ, delta.cacheReadTokens());
        hash.increment(key, F_CACHED, delta.cachedTokens());
        hash.increment(key, F_REASONING, delta.reasoningTokens());
        hash.put(key, F_UPDATED_AT, Long.toString(nowEpochMs));
        redis.opsForZSet().add(INDEX_KEY, executionId, nowEpochMs);
        redis.expire(key, SAFETY_TTL_HOURS, TimeUnit.HOURS);
    }

    /** Current accrual for an execution, or empty if none is recorded. */
    public Optional<AccruedSnapshot> snapshot(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        Map<Object, Object> h = redis.opsForHash().entries(KEY_PREFIX + executionId);
        if (h == null || h.isEmpty()) {
            return Optional.empty();
        }
        AccruedUsage usage = new AccruedUsage(
                parse(h.get(F_PROMPT)), parse(h.get(F_COMPLETION)),
                parse(h.get(F_CACHE_CREATION)), parse(h.get(F_CACHE_READ)),
                parse(h.get(F_CACHED)), parse(h.get(F_REASONING)));
        return Optional.of(new AccruedSnapshot(
                str(h.get(F_USER)), str(h.get(F_PROVIDER)), str(h.get(F_MODEL)),
                usage, parse(h.get(F_UPDATED_AT))));
    }

    /** Drop an execution's accrual (after a successful settle/release, or post-reaper). */
    public void remove(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        redis.delete(KEY_PREFIX + executionId);
        redis.opsForZSet().remove(INDEX_KEY, executionId);
    }

    /**
     * Execution ids last updated at or before {@code olderThanEpochMs} - abandoned executions the
     * reaper should settle. Bounded by {@code limit} to keep a sweep cheap.
     */
    public List<String> findStale(long olderThanEpochMs, int limit) {
        Set<String> ids = redis.opsForZSet().rangeByScore(
                INDEX_KEY, Double.NEGATIVE_INFINITY, olderThanEpochMs, 0, Math.max(1, limit));
        return ids == null ? List.of() : List.copyOf(ids);
    }

    private static long parse(Object v) {
        if (v == null) return 0L;
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
