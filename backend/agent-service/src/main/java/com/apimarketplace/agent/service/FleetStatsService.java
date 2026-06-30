package com.apimarketplace.agent.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Assembles the fleet batch stats payload for {@code GET /agents/stats}.
 *
 * <p>Replaces the controller's four <i>sequential</i> GROUP-BY calls (wall-clock = the
 * sum of all four scans over the org's whole execution history) with three layers:
 *
 * <ol>
 *   <li><b>Cache-aside</b> - a short-TTL Redis entry keyed by workspace
 *       ({@link FleetStatsCacheService}); a hit returns instantly without touching the
 *       DB, so repeat fleet opens within the TTL are free.</li>
 *   <li><b>Single-flight per workspace</b> - concurrent cache misses for the same org
 *       collapse onto ONE computation: the first caller (the "leader") computes while
 *       the others await its result. Without this, a cold-cache window (e.g. right after
 *       an execution finalize evicts the entry) lets N simultaneous fleet opens each fan
 *       out their own four scans - N×4 concurrent queries. With it, that window costs a
 *       single set of four scans regardless of how many viewers arrive.</li>
 *   <li><b>Parallel compute on miss</b> - the leader runs the four aggregations
 *       concurrently on a small dedicated pool (wall-clock ≈ the slowest single query,
 *       not the sum).</li>
 * </ol>
 *
 * <p><b>Why a dedicated, bounded pool</b> (4 daemon threads) rather than the JDK common
 * pool or an unbounded {@code @Async} executor: each aggregation opens its own read-only
 * JDBC connection, so the pool size hard-caps the worker threads this feature spawns.
 * The leader runs on the calling (request) thread and fans the four queries out to the
 * pool, so no pool slot is wasted on orchestration and there is no risk of the pool
 * deadlocking on itself. (The four scans still each borrow a shared Hikari connection
 * while they run - the bound limits, but does not eliminate, connection pressure; the
 * cache + single-flight keep that pressure near zero in steady state.)
 *
 * <p>Each task calls a <b>proxied</b> {@code @Transactional(readOnly = true)} method on
 * {@link AgentMetricsQueryService}, so every worker thread gets its own transaction and
 * EntityManager - the queries take {@code tenantId}/{@code organizationId} as explicit
 * parameters and read no thread-local scope, so nothing needs propagating across threads.
 */
@Service
public class FleetStatsService {

    private static final Logger logger = LoggerFactory.getLogger(FleetStatsService.class);
    private static final int POOL_SIZE = 4; // one slot per aggregation

    private final AgentMetricsQueryService metricsQueryService;
    private final FleetStatsCacheService cacheService;
    private final ExecutorService executor;

    /** In-flight computations keyed by workspace - the single-flight registry. */
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> inFlight =
        new ConcurrentHashMap<>();

    public FleetStatsService(AgentMetricsQueryService metricsQueryService,
                             FleetStatsCacheService cacheService) {
        this.metricsQueryService = metricsQueryService;
        this.cacheService = cacheService;
        this.executor = Executors.newFixedThreadPool(POOL_SIZE, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fleet-stats-" + counter.incrementAndGet());
                t.setDaemon(true); // never block JVM shutdown on an in-flight stats compute
                return t;
            }
        });
    }

    /**
     * The fleet batch payload: {@code {toolStats, resourceStats, subAgentStats,
     * modelStats}}, each a list of per-agent rows keyed by {@code agentId}. Served from
     * cache when warm; otherwise computed once (single-flighted) and cached.
     */
    public Map<String, Object> getFleetStats(String tenantId, String organizationId) {
        Optional<Map<String, Object>> cached = cacheService.get(organizationId);
        if (cached.isPresent()) {
            return cached.get();
        }
        return computeSingleFlight(tenantId, organizationId);
    }

    /**
     * Ensures only one computation per workspace runs at a time: the first caller becomes
     * the leader and computes; concurrent callers for the same org await the leader's
     * result instead of launching their own scans.
     */
    private Map<String, Object> computeSingleFlight(String tenantId, String organizationId) {
        CompletableFuture<Map<String, Object>> promise = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> leader = inFlight.putIfAbsent(organizationId, promise);
        if (leader != null) {
            // Someone else is already computing this workspace - ride their result.
            return leader.join();
        }
        try {
            Map<String, Object> result = computeParallel(tenantId, organizationId);
            promise.complete(result);
            return result;
        } catch (RuntimeException ex) {
            promise.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(organizationId, promise);
        }
    }

    /** Run the four aggregations in parallel, assemble, and cache a COMPLETE result. */
    private Map<String, Object> computeParallel(String tenantId, String organizationId) {
        CompletableFuture<List<Map<String, Object>>> toolsFuture = submit(
            () -> metricsQueryService.getAllToolStatsByAgent(tenantId, organizationId), "toolStats");
        CompletableFuture<List<Map<String, Object>>> resourcesFuture = submit(
            () -> metricsQueryService.getAllResourceStatsByAgent(tenantId, organizationId), "resourceStats");
        CompletableFuture<List<Map<String, Object>>> subAgentsFuture = submit(
            () -> metricsQueryService.getAllSubAgentCallStats(tenantId, organizationId), "subAgentStats");
        CompletableFuture<List<Map<String, Object>>> modelsFuture = submit(
            () -> metricsQueryService.getAllModelStatsByAgent(tenantId, organizationId), "modelStats");

        List<Map<String, Object>> tools = toolsFuture.join();
        List<Map<String, Object>> resources = resourcesFuture.join();
        List<Map<String, Object>> subAgents = subAgentsFuture.join();
        List<Map<String, Object>> models = modelsFuture.join();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolStats", tools != null ? tools : List.of());
        out.put("resourceStats", resources != null ? resources : List.of());
        out.put("subAgentStats", subAgents != null ? subAgents : List.of());
        out.put("modelStats", models != null ? models : List.of());

        // Only cache a COMPLETE result. If any aggregation failed (returned null), serve
        // the partial payload this once but don't pin a degraded view for the whole TTL -
        // the next request retries the DB.
        boolean complete = tools != null && resources != null && subAgents != null && models != null;
        if (complete) {
            cacheService.put(organizationId, out);
        }
        return out;
    }

    /**
     * Run one aggregation on the dedicated pool. A query failure degrades that single
     * group to {@code null} (logged) rather than failing the whole endpoint - the fleet
     * badges are non-critical overlays and the caller renders an empty group for a failed
     * slice. A {@link RejectedExecutionException} (only possible during JVM shutdown, once
     * the pool is closing) likewise degrades to an empty group instead of 500-ing the
     * request.
     */
    private CompletableFuture<List<Map<String, Object>>> submit(
            java.util.function.Supplier<List<Map<String, Object>>> query, String label) {
        try {
            return CompletableFuture.supplyAsync(query, executor)
                .exceptionally(ex -> {
                    // supplyAsync wraps the cause in CompletionException whose message is
                    // often null - log the throwable itself for a usable diagnostic.
                    logger.warn("Fleet stats aggregation '{}' failed: {}", label, String.valueOf(ex));
                    return null;
                });
        } catch (RejectedExecutionException ex) {
            logger.warn("Fleet stats aggregation '{}' rejected (executor shutting down)", label);
            return CompletableFuture.completedFuture(null);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
