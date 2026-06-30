package com.apimarketplace.common.scaling.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * In-memory implementation of {@link DistributedSemaphore} backed by Java Semaphores.
 *
 * <p>Suitable for single-instance deployments. The ownerId parameter is accepted
 * but not tracked - Java Semaphore handles permit counting internally.
 * Over-release is guarded: release is skipped if available permits already equal max.
 *
 * <p>Registered as a bean via {@link com.apimarketplace.common.scaling.ScalingAutoConfiguration}
 * with {@code @ConditionalOnMissingBean}.
 */
public class InMemorySemaphore implements DistributedSemaphore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySemaphore.class);

    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> maxPermitsMap = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int maxPermits, String ownerId) {
        maxPermitsMap.putIfAbsent(key, maxPermits);
        Semaphore semaphore = semaphores.computeIfAbsent(key, k -> new Semaphore(maxPermits));
        return semaphore.tryAcquire();
    }

    @Override
    public void release(String key, String ownerId) {
        Semaphore semaphore = semaphores.get(key);
        if (semaphore != null) {
            int max = maxPermitsMap.getOrDefault(key, 1);
            // Synchronized to prevent TOCTOU race: without this, two threads could both
            // see availablePermits() < max and both call release(), exceeding maxPermits.
            synchronized (semaphore) {
                if (semaphore.availablePermits() >= max) {
                    log.debug("[InMemorySemaphore] Skipping release (already at max): key={}, available={}, max={}",
                            key, semaphore.availablePermits(), max);
                    return;
                }
                semaphore.release();
            }
        }
    }

    @Override
    public int availablePermits(String key, int maxPermits) {
        Semaphore semaphore = semaphores.get(key);
        if (semaphore == null) {
            return maxPermits;
        }
        return semaphore.availablePermits();
    }

    @Override
    public void cleanupByPrefix(String keyPrefix) {
        semaphores.keySet().removeIf(k -> k.startsWith(keyPrefix));
        maxPermitsMap.keySet().removeIf(k -> k.startsWith(keyPrefix));
    }
}
