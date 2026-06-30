package com.apimarketplace.common.event;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Abstraction for key-value storage with TTL support.
 * <p>
 * EE: backed by Redis (String/Hash/List/Set operations).
 * CE: backed by Caffeine + ConcurrentHashMap.
 * <p>
 * Supports the data structures used across the platform:
 * - Simple key-value (cancel signals, counters)
 * - Hash maps (session data, metadata)
 * - Lists (content accumulation, replay)
 * - Sets (indexes)
 */
public interface KeyValueStore {

    // === Simple key-value operations ===

    void set(String key, String value, Duration ttl);

    Optional<String> get(String key);

    boolean exists(String key);

    void delete(String key);

    /**
     * Atomically increment a counter key. Creates key with value 1 if it doesn't exist.
     *
     * @return the value after increment
     */
    long increment(String key, Duration ttl);

    // === Hash operations ===

    void hashPutAll(String key, Map<String, String> fields, Duration ttl);

    void hashPut(String key, String field, String value);

    Map<String, String> hashGetAll(String key);

    Optional<String> hashGet(String key, String field);

    // === List operations ===

    void listRightPush(String key, String value);

    List<String> listRange(String key, long start, long end);

    long listSize(String key);

    void listTrim(String key, long start, long end);

    // === Set operations ===

    void setAdd(String key, String... values);

    void setRemove(String key, String... values);

    Set<String> setMembers(String key);

    boolean setIsMember(String key, String value);

    // === TTL management ===

    void expire(String key, Duration ttl);

    /**
     * Delete all keys matching a pattern (e.g. "prefix:*").
     * Use sparingly - can be expensive.
     */
    void deleteByPattern(String pattern);
}
