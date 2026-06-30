package com.apimarketplace.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory KeyValueStore for CE monolith mode.
 * Uses ConcurrentHashMap with TTL-based expiry managed by a background cleanup thread.
 */
public class InMemoryKeyValueStore implements KeyValueStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryKeyValueStore.class);
    private static final long CLEANUP_INTERVAL_SECONDS = 30;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public InMemoryKeyValueStore() {
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kv-store-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::cleanup, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // === Simple key-value ===

    @Override
    public void set(String key, String value, Duration ttl) {
        store.put(key, Entry.ofString(value, ttl));
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = getIfNotExpired(key);
        if (entry == null) return Optional.empty();
        return Optional.ofNullable(entry.stringValue);
    }

    @Override
    public boolean exists(String key) {
        return getIfNotExpired(key) != null;
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        Entry entry = store.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired()) {
                Entry e = Entry.ofCounter(1, ttl);
                return e;
            }
            existing.counter.incrementAndGet();
            return existing;
        });
        return entry.counter.get();
    }

    // === Hash operations ===

    @Override
    public void hashPutAll(String key, Map<String, String> fields, Duration ttl) {
        store.compute(key, (k, existing) -> {
            Entry entry = (existing != null && !existing.isExpired()) ? existing : Entry.ofHash(ttl);
            entry.hashValue.putAll(fields);
            if (ttl != null) entry.expiresAt = Instant.now().plus(ttl);
            return entry;
        });
    }

    @Override
    public void hashPut(String key, String field, String value) {
        store.compute(key, (k, existing) -> {
            Entry entry = (existing != null && !existing.isExpired()) ? existing : Entry.ofHash(null);
            entry.hashValue.put(field, value);
            return entry;
        });
    }

    @Override
    public Map<String, String> hashGetAll(String key) {
        Entry entry = getIfNotExpired(key);
        if (entry == null || entry.hashValue == null) return Map.of();
        return new HashMap<>(entry.hashValue);
    }

    @Override
    public Optional<String> hashGet(String key, String field) {
        Entry entry = getIfNotExpired(key);
        if (entry == null || entry.hashValue == null) return Optional.empty();
        return Optional.ofNullable(entry.hashValue.get(field));
    }

    // === List operations ===

    @Override
    public void listRightPush(String key, String value) {
        store.compute(key, (k, existing) -> {
            Entry entry = (existing != null && !existing.isExpired()) ? existing : Entry.ofList(null);
            synchronized (entry.listValue) {
                entry.listValue.add(value);
            }
            return entry;
        });
    }

    @Override
    public List<String> listRange(String key, long start, long end) {
        Entry entry = getIfNotExpired(key);
        if (entry == null || entry.listValue == null) return List.of();
        synchronized (entry.listValue) {
            int size = entry.listValue.size();
            int s = (int) Math.max(0, start);
            int e = (end < 0) ? size : (int) Math.min(end + 1, size);
            if (s >= size || s >= e) return List.of();
            return new ArrayList<>(entry.listValue.subList(s, e));
        }
    }

    @Override
    public long listSize(String key) {
        Entry entry = getIfNotExpired(key);
        if (entry == null || entry.listValue == null) return 0;
        return entry.listValue.size();
    }

    @Override
    public void listTrim(String key, long start, long end) {
        Entry entry = getIfNotExpired(key);
        if (entry == null || entry.listValue == null) return;
        synchronized (entry.listValue) {
            int size = entry.listValue.size();
            int s = (int) Math.max(0, start);
            int e = (end < 0) ? size : (int) Math.min(end + 1, size);
            List<String> trimmed = new ArrayList<>(entry.listValue.subList(s, Math.min(e, size)));
            entry.listValue.clear();
            entry.listValue.addAll(trimmed);
        }
    }

    // === Set operations ===

    @Override
    public void setAdd(String key, String... values) {
        store.compute(key, (k, existing) -> {
            Entry entry = (existing != null && !existing.isExpired()) ? existing : Entry.ofSet(null);
            Collections.addAll(entry.setValue, values);
            return entry;
        });
    }

    @Override
    public void setRemove(String key, String... values) {
        Entry entry = getIfNotExpired(key);
        if (entry == null || entry.setValue == null) return;
        for (String v : values) {
            entry.setValue.remove(v);
        }
    }

    @Override
    public Set<String> setMembers(String key) {
        Entry entry = getIfNotExpired(key);
        if (entry == null || entry.setValue == null) return Set.of();
        return new HashSet<>(entry.setValue);
    }

    @Override
    public boolean setIsMember(String key, String value) {
        Entry entry = getIfNotExpired(key);
        if (entry == null || entry.setValue == null) return false;
        return entry.setValue.contains(value);
    }

    // === TTL ===

    @Override
    public void expire(String key, Duration ttl) {
        Entry entry = store.get(key);
        if (entry != null) {
            entry.expiresAt = Instant.now().plus(ttl);
        }
    }

    @Override
    public void deleteByPattern(String pattern) {
        String regex = "^" + pattern.replace("*", ".*") + "$";
        store.keySet().removeIf(key -> key.matches(regex));
    }

    // === Internal ===

    private Entry getIfNotExpired(String key) {
        Entry entry = store.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            store.remove(key);
            return null;
        }
        return entry;
    }

    private void cleanup() {
        int removed = 0;
        for (Map.Entry<String, Entry> e : store.entrySet()) {
            if (e.getValue().isExpired()) {
                store.remove(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Cleaned up {} expired keys", removed);
        }
    }

    /**
     * Multi-type entry that can hold String, Hash, List, Set, or Counter.
     */
    private static class Entry {
        String stringValue;
        ConcurrentHashMap<String, String> hashValue;
        List<String> listValue;
        Set<String> setValue;
        AtomicLong counter;
        volatile Instant expiresAt;

        boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }

        static Entry ofString(String value, Duration ttl) {
            Entry e = new Entry();
            e.stringValue = value;
            if (ttl != null) e.expiresAt = Instant.now().plus(ttl);
            return e;
        }

        static Entry ofHash(Duration ttl) {
            Entry e = new Entry();
            e.hashValue = new ConcurrentHashMap<>();
            if (ttl != null) e.expiresAt = Instant.now().plus(ttl);
            return e;
        }

        static Entry ofList(Duration ttl) {
            Entry e = new Entry();
            e.listValue = Collections.synchronizedList(new ArrayList<>());
            if (ttl != null) e.expiresAt = Instant.now().plus(ttl);
            return e;
        }

        static Entry ofSet(Duration ttl) {
            Entry e = new Entry();
            e.setValue = ConcurrentHashMap.newKeySet();
            if (ttl != null) e.expiresAt = Instant.now().plus(ttl);
            return e;
        }

        static Entry ofCounter(long initial, Duration ttl) {
            Entry e = new Entry();
            e.counter = new AtomicLong(initial);
            if (ttl != null) e.expiresAt = Instant.now().plus(ttl);
            return e;
        }
    }
}
