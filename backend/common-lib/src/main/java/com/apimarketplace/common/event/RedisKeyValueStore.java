package com.apimarketplace.common.event;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Redis-backed KeyValueStore for EE microservice mode.
 * Wraps StringRedisTemplate operations (all values as Strings).
 * Key methods are instrumented with Micrometer timers for observability.
 */
public class RedisKeyValueStore implements KeyValueStore {

    private static final Logger log = LoggerFactory.getLogger(RedisKeyValueStore.class);
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public RedisKeyValueStore(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    // === Simple key-value ===

    @Override
    public void set(String key, String value, Duration ttl) {
        timed("set", () -> {
            if (ttl != null) {
                redisTemplate.opsForValue().set(key, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
        });
    }

    @Override
    public Optional<String> get(String key) {
        return timedSupplier("get", () -> Optional.ofNullable(redisTemplate.opsForValue().get(key)));
    }

    @Override
    public boolean exists(String key) {
        return timedSupplier("exists", () -> Boolean.TRUE.equals(redisTemplate.hasKey(key)));
    }

    @Override
    public void delete(String key) {
        timed("delete", () -> redisTemplate.delete(key));
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long result = redisTemplate.opsForValue().increment(key);
        if (ttl != null) {
            redisTemplate.expire(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
        }
        return result != null ? result : 0;
    }

    // === Hash operations ===

    @Override
    public void hashPutAll(String key, Map<String, String> fields, Duration ttl) {
        timed("hashPutAll", () -> {
            redisTemplate.opsForHash().putAll(key, fields);
            if (ttl != null) {
                redisTemplate.expire(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
            }
        });
    }

    @Override
    public void hashPut(String key, String field, String value) {
        timed("hashPut", () -> redisTemplate.opsForHash().put(key, field, value));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> hashGetAll(String key) {
        return timedSupplier("hashGetAll", () -> {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            Map<String, String> result = new HashMap<>();
            entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
            return result;
        });
    }

    @Override
    public Optional<String> hashGet(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        return Optional.ofNullable(value != null ? value.toString() : null);
    }

    // === List operations ===

    @Override
    public void listRightPush(String key, String value) {
        timed("listRightPush", () -> redisTemplate.opsForList().rightPush(key, value));
    }

    @Override
    public List<String> listRange(String key, long start, long end) {
        List<String> result = redisTemplate.opsForList().range(key, start, end);
        return result != null ? result : List.of();
    }

    @Override
    public long listSize(String key) {
        Long size = redisTemplate.opsForList().size(key);
        return size != null ? size : 0;
    }

    @Override
    public void listTrim(String key, long start, long end) {
        redisTemplate.opsForList().trim(key, start, end);
    }

    // === Set operations ===

    @Override
    public void setAdd(String key, String... values) {
        redisTemplate.opsForSet().add(key, values);
    }

    @Override
    public void setRemove(String key, String... values) {
        redisTemplate.opsForSet().remove(key, (Object[]) values);
    }

    @Override
    public Set<String> setMembers(String key) {
        Set<String> members = redisTemplate.opsForSet().members(key);
        return members != null ? members : Set.of();
    }

    @Override
    public boolean setIsMember(String key, String value) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, value));
    }

    // === TTL ===

    @Override
    public void expire(String key, Duration ttl) {
        redisTemplate.expire(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void deleteByPattern(String pattern) {
        timed("deleteByPattern", () -> {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        });
    }

    // === Timing helpers ===

    private void timed(String operation, Runnable action) {
        Timer.builder("scaling.redis.kvstore")
                .description("Time for Redis KeyValueStore operation")
                .tag("operation", operation)
                .register(meterRegistry)
                .record(action);
    }

    private <T> T timedSupplier(String operation, Supplier<T> action) {
        return Timer.builder("scaling.redis.kvstore")
                .description("Time for Redis KeyValueStore operation")
                .tag("operation", operation)
                .register(meterRegistry)
                .record(action);
    }
}
