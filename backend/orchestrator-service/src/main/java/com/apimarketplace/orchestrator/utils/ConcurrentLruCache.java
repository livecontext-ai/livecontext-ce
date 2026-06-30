package com.apimarketplace.orchestrator.utils;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Simple thread-safe LRU cache backed by a ConcurrentHashMap and
 * a lock-free deque for eviction. This avoids bringing an external
 * dependency while still keeping memory under control.
 */
public final class ConcurrentLruCache<K, V> {

    private final int maxSize;
    private final ConcurrentMap<K, V> store = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<K> evictionQueue = new ConcurrentLinkedDeque<>();

    public ConcurrentLruCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        this.maxSize = maxSize;
    }

    public V computeIfAbsent(K key, Function<K, V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        V existing = store.get(key);
        if (existing != null) {
            return existing;
        }

        V value = loader.apply(key);
        V previous = store.putIfAbsent(key, value);
        if (previous != null) {
            return previous;
        }

        evictionQueue.addLast(key);
        trimToSize();
        return value;
    }

    public void clear() {
        store.clear();
        evictionQueue.clear();
    }

    public int size() {
        return store.size();
    }

    public boolean containsKey(K key) {
        return store.containsKey(key);
    }

    private void trimToSize() {
        while (store.size() > maxSize) {
            K eldest = evictionQueue.pollFirst();
            if (eldest == null) {
                break;
            }
            store.remove(eldest);
        }
    }
}
