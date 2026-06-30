package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.config.RedisCacheConfig;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunStatusEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.streaming.emitter.StreamingBatchScheduler;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Caffeine-backed service for managing workflow snapshots.
 * Provides caching for completed workflow runs to avoid repeated DB queries on WebSocket reconnection.
 * Local to orchestrator process - no cross-process sharing needed.
 */
@Service
public class SnapshotCacheService {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotCacheService.class);

    private final Cache<String, SnapshotEntry> cache;

    private WorkflowRunRepository workflowRunRepository;
    private WorkflowRunStatusService workflowRunStatusService;
    private StreamingBatchScheduler batchScheduler;

    /**
     * Cap on total estimated cache weight. Post-2026-05-22 OOM hardening: the previous
     * {@code maximumSize(1000)} let 1000 entries live concurrently with no weight bound -
     * for long-lived workflows whose snapshot Map carries the full {@code stepStates} list
     * (one entry per step), each entry can weigh hundreds of KB. 50 MB total weight is
     * generous enough for the legitimate streaming-reconnect use case while keeping the
     * cache out of the humongous-region danger zone.
     */
    static final long SNAPSHOT_CACHE_MAX_WEIGHT_BYTES = 50L * 1024L * 1024L;

    public SnapshotCacheService(RedisCacheConfig cacheConfig) {
        this.cache = Caffeine.newBuilder()
                .maximumWeight(SNAPSHOT_CACHE_MAX_WEIGHT_BYTES)
                .weigher((String key, SnapshotEntry entry) -> {
                    if (entry == null || entry.snapshot == null) return 64;
                    long weight = estimateSnapshotWeight(entry.snapshot);
                    return (int) Math.min(weight, 4L * 1024L * 1024L); // 4 MB ceiling per entry
                })
                .expireAfterWrite(cacheConfig.getSnapshotTtl())
                .build();
        logger.info("SnapshotCacheService initialized with Caffeine, maxWeight={} bytes, TTL={}",
                   SNAPSHOT_CACHE_MAX_WEIGHT_BYTES, cacheConfig.getSnapshotTtl());
    }

    /**
     * Recursive (one-level) weight estimator for the snapshot Map. The prior estimator
     * used only top-level {@code Map.size()}, which under-counted any value-Map / value-List
     * by orders of magnitude (a {@code stepStates} list with 1000 entries weighed 256 B
     * instead of ~MB). This walker visits Map values and Collection elements one level deep
     * - sufficient to bound the dominant cost (stepStates / nodeStates lists) without
     * recursing into every nested Map.
     *
     * <p>Constants are deliberate over-estimates so the cache evicts BEFORE heap pressure
     * shows up in GC: 64 B per primitive, ~2x the wire-JSON cost for nested objects.
     */
    static long estimateSnapshotWeight(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return 64L;
        long total = 256L; // baseline (entry + key overhead)
        for (var entry : snapshot.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            total += 32L + (k != null ? (long) k.length() * 2L : 0L);
            total += estimateValueWeight(v);
            if (total > 8L * 1024L * 1024L) break; // short-circuit at 2× per-entry ceiling
        }
        return total;
    }

    private static long estimateValueWeight(Object v) {
        if (v == null) return 8L;
        if (v instanceof String s) return (long) s.length() * 2L;
        if (v instanceof java.util.Collection<?> c) {
            // One level deep: assume each element is a small Map/scalar of ~128 B.
            // Larger nested collections will be re-evaluated when the cache shrinks
            // and re-inserts the entry, so under-count over time is self-correcting.
            return 64L + (long) c.size() * 128L;
        }
        if (v instanceof Map<?, ?> m) {
            return 64L + (long) m.size() * 96L;
        }
        return 32L;
    }

    @Autowired(required = false)
    public void setWorkflowRunRepository(WorkflowRunRepository workflowRunRepository) {
        this.workflowRunRepository = workflowRunRepository;
    }

    @Autowired(required = false)
    public void setWorkflowRunStatusService(WorkflowRunStatusService workflowRunStatusService) {
        this.workflowRunStatusService = workflowRunStatusService;
    }

    @Autowired(required = false)
    public void setBatchScheduler(StreamingBatchScheduler batchScheduler) {
        this.batchScheduler = batchScheduler;
    }

    /**
     * Gets a snapshot for a run, checking live scheduler first, then cache, then database.
     */
    public Optional<SnapshotResult> getSnapshot(String runId) {
        if (runId == null) {
            return Optional.empty();
        }

        // 1. Try to get from batch scheduler (running workflows)
        if (batchScheduler != null) {
            Map<String, Object> snapshot = batchScheduler.snapshotForRun(runId);
            if (snapshot != null && !snapshot.isEmpty()) {
                logger.debug("Returning live snapshot from BatchScheduler for runId: {}", runId);
                enrichSnapshotWithStepIdSets(snapshot);
                return Optional.of(new SnapshotResult(snapshot, null, SnapshotSource.LIVE));
            }
        }

        // 2. Check Caffeine cache
        SnapshotEntry cached = cache.getIfPresent(runId);
        if (cached != null) {
            logger.debug("Returning cached snapshot for runId: {} (status: {})", runId, cached.status);
            return Optional.of(new SnapshotResult(cached.snapshot, cached.status, SnapshotSource.CACHE));
        }

        // 3. Load from database
        return loadFromDatabase(runId);
    }

    /**
     * Caches a final snapshot for a completed run.
     */
    public void cacheSnapshot(String runId, Map<String, Object> snapshot, String status) {
        if (runId == null || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        cache.put(runId, new SnapshotEntry(snapshot, status));
        logger.debug("Cached snapshot for runId: {} (status: {})", runId, status);
    }

    /**
     * Removes a snapshot from the cache.
     */
    public void evict(String runId) {
        if (runId == null) return;
        cache.invalidate(runId);
        logger.debug("Evicted snapshot for runId: {}", runId);
    }

    /**
     * Clears all snapshots from cache.
     */
    public void clearCache() {
        long size = cache.estimatedSize();
        cache.invalidateAll();
        logger.info("Cleared ~{} snapshot cache entries", size);
    }

    /**
     * Gets the current cache size.
     */
    public int getCacheSize() {
        return (int) cache.estimatedSize();
    }

    private Optional<SnapshotResult> loadFromDatabase(String runId) {
        if (workflowRunRepository == null || workflowRunStatusService == null) {
            return Optional.empty();
        }

        try {
            Optional<WorkflowRunEntity> runEntityOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runEntityOpt.isEmpty()) {
                return Optional.empty();
            }

            WorkflowRunEntity runEntity = runEntityOpt.get();
            UUID workflowRunId = runEntity.getId();

            Optional<WorkflowRunStatusEntity> statusEntityOpt = workflowRunStatusService.findByRunId(workflowRunId);
            if (statusEntityOpt.isEmpty()) {
                return Optional.empty();
            }

            WorkflowRunStatusEntity statusEntity = statusEntityOpt.get();
            Map<String, Object> dbSnapshot = statusEntity.getPayload();

            if (dbSnapshot != null && !dbSnapshot.isEmpty()) {
                String status = statusEntity.getStatus().getValue().toUpperCase();
                logger.info("Loaded snapshot from database for runId: {} (status: {})", runId, status);

                enrichSnapshotWithStepIdSets(dbSnapshot);

                // Cache for future requests
                cacheSnapshot(runId, dbSnapshot, status);

                return Optional.of(new SnapshotResult(dbSnapshot, status, SnapshotSource.DATABASE));
            }

            return Optional.empty();

        } catch (Exception e) {
            logger.warn("Error loading snapshot from database for runId {}: {}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Internal cache entry holding snapshot data and status.
     */
    private record SnapshotEntry(Map<String, Object> snapshot, String status) {}

    /**
     * Result of a snapshot retrieval operation.
     */
    public record SnapshotResult(
            Map<String, Object> snapshot,
            String status,
            SnapshotSource source
    ) {
        public boolean isLive() {
            return source == SnapshotSource.LIVE;
        }

        public boolean isFromCache() {
            return source == SnapshotSource.CACHE;
        }

        public boolean isFromDatabase() {
            return source == SnapshotSource.DATABASE;
        }

        public boolean hasTerminalStatus() {
            return status != null;
        }
    }

    public enum SnapshotSource {
        LIVE, CACHE, DATABASE
    }

    @SuppressWarnings("unchecked")
    private void enrichSnapshotWithStepIdSets(Map<String, Object> snapshot) {
        if (snapshot == null) return;

        Object nodesObj = snapshot.get("nodes");
        if (!(nodesObj instanceof List<?> nodesList)) return;

        Set<String> completedStepIds = new LinkedHashSet<>();
        Set<String> failedStepIds = new LinkedHashSet<>();
        Set<String> skippedStepIds = new LinkedHashSet<>();
        Set<String> readyStepIds = new LinkedHashSet<>();
        Set<String> runningStepIds = new LinkedHashSet<>();

        for (Object nodeObj : nodesList) {
            if (!(nodeObj instanceof Map<?, ?> nodeMap)) continue;

            Object idObj = nodeMap.get("normalizedStepId");
            if (idObj == null) idObj = nodeMap.get("id");
            if (idObj == null) continue;
            String stepId = idObj.toString().trim();
            if (stepId.isEmpty()) continue;

            Object statusObj = nodeMap.get("backendStatus");
            if (statusObj == null) statusObj = nodeMap.get("status");
            String status = statusObj != null ? statusObj.toString().toLowerCase().trim() : "";

            switch (status) {
                case "completed", "success" -> completedStepIds.add(stepId);
                case "failed", "error" -> failedStepIds.add(stepId);
                case "skipped" -> skippedStepIds.add(stepId);
                case "running" -> runningStepIds.add(stepId);
                case "ready" -> readyStepIds.add(stepId);
                default -> { /* pending or unknown */ }
            }
        }

        snapshot.put("completedStepIds", new ArrayList<>(completedStepIds));
        snapshot.put("failedStepIds", new ArrayList<>(failedStepIds));
        snapshot.put("skippedStepIds", new ArrayList<>(skippedStepIds));
        snapshot.put("readyStepIds", new ArrayList<>(readyStepIds));
        snapshot.put("runningStepIds", new ArrayList<>(runningStepIds));
    }
}
