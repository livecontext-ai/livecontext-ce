package com.apimarketplace.orchestrator.trigger.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Publisher-layer for ExecutionQueue Prometheus metrics.
 *
 * <p>Emits two series per event: one tagged with the real {@code tenant} (if present),
 * and one tagged with {@code tenant="all"} (aggregate). Mirrors the multi-tenant pattern
 * used by {@link com.apimarketplace.agent.ratelimit.RateLimitMetrics}.
 *
 * <p>Cardinality policy: the {@code plan} tag is bounded (9 known plan codes + "all"),
 * the {@code tenant} tag is unbounded per-platform but follows the same pattern we
 * validated for the LLM rate limiter. Reserved {@code tenantId="all"} collides with
 * real callers using that literal id - do not assign that literal to a real tenant.
 */
@Component
public class ExecutionQueueMetrics {

    // --- Metric names ---
    public static final String ENQUEUED_TOTAL = "workflow_queue_enqueued_total";
    public static final String COMPLETED_TOTAL = "workflow_queue_completed_total";
    public static final String WAIT_MS_TOTAL = "workflow_queue_wait_ms_total";
    public static final String DEPTH = "workflow_queue_depth";
    public static final String WORKERS_AVAILABLE = "workflow_queue_workers_available";
    public static final String WORKERS_BUSY = "workflow_queue_workers_busy";
    public static final String ACTIVE_TENANTS = "workflow_queue_active_tenants";

    // --- Tag sentinels ---
    public static final String AGGREGATE = "all";

    // --- Path tag values ---
    public static final String PATH_FAST = "fast";
    public static final String PATH_SLOW = "slow";

    // --- Outcome tag values ---
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";
    public static final String OUTCOME_TIMEOUT = "timeout";
    public static final String OUTCOME_CANCELLED = "cancelled";

    /**
     * Known plan codes - anything outside this set is normalized to {@code UNKNOWN}
     * to keep the {@code plan} tag cardinality bounded.
     */
    private static final Set<String> KNOWN_PLANS = Set.of(
            "FREE", "STARTER", "PRO", "TEAM", "PAYG",
            "ENTERPRISE_BASIC", "ENTERPRISE_STANDARD",
            "ENTERPRISE_PREMIUM", "ENTERPRISE_ULTIMATE");
    private static final String UNKNOWN_PLAN = "UNKNOWN";

    /**
     * How long a tenant can be inactive before it's evicted from
     * {@link #recentlyActiveTenants}. Series in Prometheus stay (they naturally
     * go to zero rate) but the gauge reflects only currently-active traffic.
     */
    private static final long TENANT_TTL_MS = 15 * 60 * 1000L;

    private final MeterRegistry registry;
    private final Map<String, Long> recentlyActiveTenants = new ConcurrentHashMap<>();
    private final AtomicInteger activeTenantsGauge = new AtomicInteger(0);

    // Retain strong references to the gauge suppliers. Micrometer's Gauge.builder()
    // wraps the passed-in object in a WeakReference - if we only pass a lambda that
    // is not referenced elsewhere, it gets GC'd and the gauge returns NaN.
    private volatile IntSupplier depthSupplier;
    private volatile IntSupplier workersAvailableSupplier;
    private volatile IntSupplier workersBusySupplier;

    public ExecutionQueueMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(ACTIVE_TENANTS, activeTenantsGauge, AtomicInteger::get)
                .description("Distinct tenants that have enqueued within the last " + (TENANT_TTL_MS / 60_000) + " minutes")
                .register(registry);
    }

    /**
     * Binds queue depth / worker gauges to the running {@link ExecutionQueueService}.
     *
     * <p>Called once after construction (not in the constructor) to avoid exposing a
     * partially-initialized queue service through a strong reference from the registry.
     */
    public void bindQueueGauges(IntSupplier depth, IntSupplier workersAvailable, IntSupplier workersBusy) {
        // Store strong refs BEFORE registering - Micrometer wraps the 2nd arg in a
        // WeakReference, so an unreferenced lambda gets GC'd and the gauge returns NaN.
        this.depthSupplier = depth;
        this.workersAvailableSupplier = workersAvailable;
        this.workersBusySupplier = workersBusy;
        Gauge.builder(DEPTH, this.depthSupplier, IntSupplier::getAsInt)
                .description("Current number of executions waiting in the priority queue")
                .register(registry);
        Gauge.builder(WORKERS_AVAILABLE, this.workersAvailableSupplier, IntSupplier::getAsInt)
                .description("Worker semaphore permits currently available")
                .register(registry);
        Gauge.builder(WORKERS_BUSY, this.workersBusySupplier, IntSupplier::getAsInt)
                .description("Executions currently being run by workers (fast + slow paths)")
                .register(registry);
    }

    /**
     * Records an enqueue event. Emits per-tenant + aggregate series.
     *
     * @param plan subscription plan code (nullable → normalized to "FREE")
     * @param tenantId calling tenant id (nullable → only aggregate series is emitted)
     * @param path {@link #PATH_FAST} or {@link #PATH_SLOW}
     */
    public void recordEnqueued(String plan, String tenantId, String path) {
        String p = normalizePlan(plan);
        incrementCounter(ENQUEUED_TOTAL, "plan", p, "tenant", AGGREGATE, "path", path);
        if (isNamedTenant(tenantId)) {
            incrementCounter(ENQUEUED_TOTAL, "plan", p, "tenant", tenantId, "path", path);
            addActiveTenant(tenantId);
        }
    }

    /**
     * Records a completion. Emits per-tenant + aggregate series.
     *
     * @param outcome one of {@link #OUTCOME_SUCCESS}, {@link #OUTCOME_FAILURE},
     *                {@link #OUTCOME_TIMEOUT}, {@link #OUTCOME_CANCELLED}
     */
    public void recordCompleted(String plan, String tenantId, String outcome) {
        String p = normalizePlan(plan);
        incrementCounter(COMPLETED_TOTAL, "plan", p, "tenant", AGGREGATE, "outcome", outcome);
        if (isNamedTenant(tenantId)) {
            incrementCounter(COMPLETED_TOTAL, "plan", p, "tenant", tenantId, "outcome", outcome);
        }
    }

    /**
     * Records how long a slow-path execution waited in the queue before a worker picked it up.
     * No-op for fast-path executions (wait is zero by definition).
     */
    public void recordWaitMs(String plan, String tenantId, long waitMs) {
        if (waitMs <= 0) return;
        String p = normalizePlan(plan);
        // NOTE: no .baseUnit() - Micrometer would append `_milliseconds` to the
        // Prometheus name, yielding workflow_queue_wait_ms_total_milliseconds_total.
        // The metric name already carries the unit (`_ms_total`), so we skip it
        // to keep the exposed name stable for the Grafana dashboard.
        Counter.builder(WAIT_MS_TOTAL)
                .tags("plan", p, "tenant", AGGREGATE)
                .register(registry)
                .increment(waitMs);
        if (isNamedTenant(tenantId)) {
            Counter.builder(WAIT_MS_TOTAL)
                    .tags("plan", p, "tenant", tenantId)
                    .register(registry)
                    .increment(waitMs);
        }
    }

    private void incrementCounter(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    private void addActiveTenant(String tenantId) {
        recentlyActiveTenants.put(tenantId, System.currentTimeMillis());
        activeTenantsGauge.set(recentlyActiveTenants.size());
    }

    /**
     * Evicts tenants not seen within {@link #TENANT_TTL_MS}. Runs on the orchestrator
     * scheduler; relies on Spring's {@code @EnableScheduling} being active elsewhere.
     * The counter series themselves stay in the registry - callers query them via
     * {@code rate()} / {@code increase()} which naturally go to zero once a tenant
     * stops producing samples.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    void pruneInactiveTenants() {
        long cutoff = System.currentTimeMillis() - TENANT_TTL_MS;
        recentlyActiveTenants.entrySet().removeIf(e -> e.getValue() < cutoff);
        activeTenantsGauge.set(recentlyActiveTenants.size());
    }

    private static String normalizePlan(String plan) {
        if (plan == null || plan.isBlank()) return "FREE";
        String normalized = plan.trim().toUpperCase();
        if ("ENTERPRISE".equals(normalized)) normalized = "ENTERPRISE_BASIC";
        // Bound cardinality: unknown plan values collapse to UNKNOWN.
        return KNOWN_PLANS.contains(normalized) ? normalized : UNKNOWN_PLAN;
    }

    private static boolean isNamedTenant(String tenantId) {
        return tenantId != null && !tenantId.isBlank() && !AGGREGATE.equals(tenantId);
    }
}
