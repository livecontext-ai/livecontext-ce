package com.apimarketplace.orchestrator.services.streaming;

import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/**
 * Single-pod ({@code scaling.backend=memory}) {@link RunSeqBackend} - a per-runId
 * {@code AtomicLong}, lazily seeded from the DB high-water mark on first use. This
 * is the original {@link WsEventSequencer} behavior, extracted unchanged so the
 * memory mode (local dev, CE monolith) is byte-for-byte equivalent.
 *
 * <p>Correct ONLY for a single orchestrator instance: two pods each hold their own
 * map, so use {@link RedisRunSeqBackend} whenever {@code replicas > 1}.
 */
final class InMemoryRunSeqBackend implements RunSeqBackend {

    /** Per-runId counter. Lazily initialized; values seeded from DB on first use. */
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long next(String runId, ToLongFunction<String> seedLoader) {
        AtomicLong counter = counters.computeIfAbsent(runId,
                k -> new AtomicLong(Math.max(0L, seedLoader.applyAsLong(k))));
        return counter.incrementAndGet();
    }

    @Override
    public long current(String runId, ToLongFunction<String> seedLoader) {
        AtomicLong counter = counters.get(runId);
        if (counter != null) {
            return counter.get();
        }
        // Read-only: do NOT insert into the map for a runId we've never bumped -
        // keeps the map bounded by real run lifetime (REST /state hits arbitrary,
        // possibly-404 runIds).
        return Math.max(0L, seedLoader.applyAsLong(runId));
    }

    @Override
    public OptionalLong peek(String runId) {
        AtomicLong counter = counters.get(runId);
        return counter != null ? OptionalLong.of(counter.get()) : OptionalLong.empty();
    }

    @Override
    public void remove(String runId) {
        counters.remove(runId);
    }

    @Override
    public int size() {
        return counters.size();
    }
}
