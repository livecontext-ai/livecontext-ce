package com.apimarketplace.common.scaling.redis;

import com.apimarketplace.common.scaling.cache.DistributedBudgetCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

/**
 * Redis-backed distributed budget cache using atomic Lua scripts.
 *
 * <p>Budget values are stored in a Redis Hash ({@code orch:credit:budget})
 * with tenant IDs as fields and budget amounts as values (stored as strings
 * via {@code HINCRBYFLOAT}).
 *
 * <p>Failure strategy: FAIL-OPEN (partitioned) - on Redis failure, callers should
 * fall back to in-memory with {@code budget/N} ceiling per instance.
 */
public class RedisBudgetCache implements DistributedBudgetCache {

    private static final Logger log = LoggerFactory.getLogger(RedisBudgetCache.class);

    private static final String BUDGET_HASH_KEY = "orch:credit:budget";
    private static final String DEDUP_KEY_PREFIX = "orch:credit:dedup";

    /** CAS Lua script - compare hash field value and set atomically. Static for SHA caching. */
    private static final DefaultRedisScript<Long> CAS_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('HGET', KEYS[1], ARGV[1]) " +
            "if not v then return 0 end " +
            "local vn = tonumber(v) local en = tonumber(ARGV[2]) " +
            "if vn and en and math.abs(vn - en) < 1e-10 then " +
            "  redis.call('HSET', KEYS[1], ARGV[1], ARGV[3]) return 1 " +
            "elseif v == ARGV[2] then " +
            "  redis.call('HSET', KEYS[1], ARGV[1], ARGV[3]) return 1 " +
            "else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private final DefaultRedisScript<String> decrementScript;
    private final DefaultRedisScript<Long> refundScript;
    private final DefaultRedisScript<Long> reserveBatchScript;

    public RedisBudgetCache(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.decrementScript = LuaScriptLoader.load("budget_decrement.lua", String.class);
        this.refundScript = LuaScriptLoader.load("budget_refund.lua", Long.class);
        this.reserveBatchScript = LuaScriptLoader.load("budget_reserve_batch.lua", Long.class);
    }

    @Override
    public BigDecimal get(String key) {
        try {
            Object value = redisTemplate.opsForHash().get(BUDGET_HASH_KEY, key);
            if (value == null) return null;
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            log.error("[RedisBudgetCache] get failed (FAIL-OPEN): key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void set(String key, BigDecimal value) {
        try {
            Timer.builder("scaling.redis.budget.set")
                    .register(meterRegistry)
                    .record(() -> redisTemplate.opsForHash().put(BUDGET_HASH_KEY, key, value.toPlainString()));
            log.debug("[RedisBudgetCache] Set budget '{}' = {}", key, value);
        } catch (Exception e) {
            log.error("[RedisBudgetCache] set failed: key={}, error={}", key, e.getMessage());
        }
    }

    @Override
    public BigDecimal decrementAndGet(String key, BigDecimal amount) {
        try {
            String result = Timer.builder("scaling.redis.budget.decrement")
                    .register(meterRegistry)
                    .record(() -> redisTemplate.execute(decrementScript,
                            Collections.singletonList(BUDGET_HASH_KEY),
                            key,
                            amount.toPlainString()));

            if (result != null) {
                // Lua returns new balance (decremented) or current balance (insufficient) - both atomic
                BigDecimal value = new BigDecimal(result);
                log.debug("[RedisBudgetCache] decrementAndGet '{}' by {} -> {}", key, amount, value);
                return value;
            }
            // Key does not exist
            return null;
        } catch (Exception e) {
            log.error("[RedisBudgetCache] decrementAndGet failed: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void remove(String key) {
        try {
            redisTemplate.opsForHash().delete(BUDGET_HASH_KEY, key);
            log.debug("[RedisBudgetCache] Removed budget '{}'", key);
        } catch (Exception e) {
            log.error("[RedisBudgetCache] remove failed: key={}, error={}", key, e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return redisTemplate.opsForHash().hasKey(BUDGET_HASH_KEY, key);
        } catch (Exception e) {
            log.error("[RedisBudgetCache] exists failed: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean setIfAbsent(String key, BigDecimal value) {
        try {
            Boolean result = redisTemplate.opsForHash().putIfAbsent(BUDGET_HASH_KEY, key, value.toPlainString());
            if (Boolean.TRUE.equals(result)) {
                log.debug("[RedisBudgetCache] Set budget '{}' = {} (was absent)", key, value);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("[RedisBudgetCache] setIfAbsent failed: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean compareAndSet(String key, BigDecimal expected, BigDecimal update) {
        try {
            Long result = Timer.builder("scaling.redis.budget.cas")
                    .register(meterRegistry)
                    .record(() -> redisTemplate.execute(CAS_SCRIPT,
                            Collections.singletonList(BUDGET_HASH_KEY),
                            key,
                            expected.toPlainString(),
                            update.toPlainString()));
            boolean success = result != null && result == 1L;
            if (success) {
                log.debug("[RedisBudgetCache] CAS budget '{}': {} -> {}", key, expected, update);
            }
            return success;
        } catch (Exception e) {
            log.error("[RedisBudgetCache] compareAndSet failed: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Idempotent refund with dedup protection.
     *
     * @param tenantId  the tenant
     * @param amount    the amount to refund
     * @param requestId unique request ID for idempotency
     * @return true if the refund was applied
     */
    public boolean refund(String tenantId, BigDecimal amount, String requestId) {
        try {
            Long result = redisTemplate.execute(refundScript,
                    Arrays.asList(BUDGET_HASH_KEY, DEDUP_KEY_PREFIX),
                    tenantId,
                    amount.toPlainString(),
                    requestId);
            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("[RedisBudgetCache] refund failed: tenant={}, error={}", tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Atomic partial batch reservation.
     *
     * @param tenantId    the tenant
     * @param costPerItem cost per item
     * @param itemCount   requested item count
     * @return number of affordable items (0 to itemCount)
     */
    public long reserveBatch(String tenantId, BigDecimal costPerItem, int itemCount) {
        try {
            Long result = redisTemplate.execute(reserveBatchScript,
                    Collections.singletonList(BUDGET_HASH_KEY),
                    tenantId,
                    costPerItem.toPlainString(),
                    String.valueOf(itemCount));
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("[RedisBudgetCache] reserveBatch failed: tenant={}, error={}", tenantId, e.getMessage());
            return 0;
        }
    }
}
