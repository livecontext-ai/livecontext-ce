package com.apimarketplace.common.scaling.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory implementation of {@link DistributedBudgetCache}.
 * Uses ConcurrentHashMap with AtomicReference for thread-safe operations.
 */
public class InMemoryBudgetCache implements DistributedBudgetCache {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBudgetCache.class);

    private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> cache = new ConcurrentHashMap<>();

    @Override
    public BigDecimal get(String key) {
        AtomicReference<BigDecimal> ref = cache.get(key);
        return ref != null ? ref.get() : null;
    }

    @Override
    public void set(String key, BigDecimal value) {
        cache.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.set(value);
                return existing;
            }
            return new AtomicReference<>(value);
        });
        log.debug("Set budget '{}' = {}", key, value);
    }

    @Override
    public BigDecimal decrementAndGet(String key, BigDecimal amount) {
        AtomicReference<BigDecimal> ref = cache.get(key);
        if (ref == null) return null;

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            BigDecimal current = ref.get();
            if (current.compareTo(amount) < 0) {
                return current;
            }
            BigDecimal newValue = current.subtract(amount);
            if (ref.compareAndSet(current, newValue)) {
                log.debug("Decremented budget '{}' by {} -> {}", key, amount, newValue);
                return newValue;
            }
        }
        log.error("CAS retry limit reached in decrementAndGet for key '{}'", key);
        AtomicReference<BigDecimal> fallback = cache.get(key);
        return fallback != null ? fallback.get() : null;
    }

    @Override
    public void remove(String key) {
        cache.remove(key);
        log.debug("Removed budget '{}'", key);
    }

    @Override
    public boolean exists(String key) {
        return cache.containsKey(key);
    }

    @Override
    public boolean setIfAbsent(String key, BigDecimal value) {
        AtomicReference<BigDecimal> existing = cache.putIfAbsent(key, new AtomicReference<>(value));
        if (existing == null) {
            log.debug("Set budget '{}' = {} (was absent)", key, value);
            return true;
        }
        return false;
    }

    private static final int MAX_CAS_RETRIES = 100;

    @Override
    public boolean compareAndSet(String key, BigDecimal expected, BigDecimal update) {
        AtomicReference<BigDecimal> ref = cache.get(key);
        if (ref == null) return false;
        // AtomicReference.compareAndSet uses == (reference equality), which fails for
        // BigDecimal since new BigDecimal("1.00") != new BigDecimal("1.00").
        // Use a CAS loop with compareTo() for value equality.
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            BigDecimal current = ref.get();
            if (current.compareTo(expected) != 0) {
                return false;
            }
            if (ref.compareAndSet(current, update)) {
                log.debug("CAS budget '{}': {} -> {}", key, expected, update);
                return true;
            }
        }
        log.error("CAS retry limit reached for key '{}'", key);
        return false;
    }
}
