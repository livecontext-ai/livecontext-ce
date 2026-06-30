package com.apimarketplace.common.scaling.lock;

import java.time.Duration;

/**
 * Distributed semaphore abstraction for controlling concurrent access to a resource.
 *
 * <p>Each permit is tracked by an ownerId, enabling crash-safe cleanup.
 * The default in-memory implementation delegates to Java Semaphores.
 * A Redis-backed implementation (ZSET with per-owner TTL) can be swapped in
 * for horizontal scaling.
 */
public interface DistributedSemaphore {

    /**
     * Try to acquire a permit for the given resource.
     *
     * @param key        resource identifier (e.g. "runId:triggerId")
     * @param maxPermits maximum concurrent permits for this resource
     * @param ownerId    unique owner identifier for this acquisition
     * @return true if a permit was acquired
     */
    boolean tryAcquire(String key, int maxPermits, String ownerId);

    /**
     * Try to acquire a permit with a resource-specific owner TTL.
     *
     * <p>Implementations without expiring permits may ignore {@code ttl}.
     */
    default boolean tryAcquire(String key, int maxPermits, String ownerId, Duration ttl) {
        return tryAcquire(key, maxPermits, ownerId);
    }

    /**
     * Release a permit for the given resource.
     *
     * @param key     resource identifier
     * @param ownerId the owner releasing the permit
     */
    void release(String key, String ownerId);

    /**
     * Get the number of available permits for a resource.
     *
     * @param key        resource identifier
     * @param maxPermits maximum concurrent permits for this resource
     * @return number of available permits
     */
    int availablePermits(String key, int maxPermits);

    /**
     * Remove all permits whose key starts with the given prefix.
     * Used for cleanup when a run is deleted/stopped.
     *
     * @param keyPrefix prefix to match (e.g. "runId:")
     */
    void cleanupByPrefix(String keyPrefix);

    /**
     * Extend the TTL of an active permit (heartbeat).
     * Only meaningful for implementations with expiring permits (e.g., Redis ZSET).
     * The default implementation is a no-op.
     *
     * @param key     resource identifier
     * @param ownerId the owner extending their permit
     * @return true if the permit was found and extended
     */
    default boolean heartbeat(String key, String ownerId) {
        return true; // No-op for in-memory
    }

    /**
     * Extend the TTL of an active permit using a resource-specific TTL.
     */
    default boolean heartbeat(String key, String ownerId, Duration ttl) {
        return heartbeat(key, ownerId);
    }
}
