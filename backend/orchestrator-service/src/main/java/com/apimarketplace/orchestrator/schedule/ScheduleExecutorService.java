package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueueRequestContext;
import com.apimarketplace.orchestrator.trigger.queue.RedisExecutionQueueService;
import com.apimarketplace.orchestrator.trigger.strategy.DispatchVerdict;
import com.apimarketplace.orchestrator.trigger.strategy.TriggerDispatchCoordinator;
import com.apimarketplace.orchestrator.trigger.strategy.TriggerExecutionStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimized daemon for scheduled workflow executions.
 * Uses trigger-service via TriggerClient for schedule CRUD operations.
 * Supports both workflow schedules (workflowId != null) and agent schedules (agentEntityId != null).
 *
 * <p>Safety guarantees:
 * <ul>
 *   <li><b>Workflow queue acceptance</b>: workflow schedules advance only after the
 *       execution queue accepts the due occurrence, so queue outages remain retryable.</li>
 *   <li><b>Deterministic workflow request id</b>: retrying the same due occurrence
 *       reuses the same execution id to avoid duplicate fires across pods.</li>
 *   <li><b>In-memory guard</b>: a {@link ConcurrentHashMap} prevents the same schedule
 *       from being executed concurrently if a previous tick is still in progress.</li>
 * </ul>
 */
@Service
public class ScheduleExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleExecutorService.class);

    private final TriggerClient triggerClient;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final ReusableTriggerService triggerService;
    private final ProductionRunResolver productionRunResolver;
    private final AgentClient agentClient;
    private final ConversationClient conversationServiceClient;
    @Autowired(required = false)
    private TriggerDispatchCoordinator dispatchCoordinator;

    @Autowired(required = false)
    private ExecutionQueue executionQueue;

    /**
     * Lifecycle gate - when WARMING (post-boot grace window) or DRAINING (shutdown
     * in progress), the tick is skipped entirely. Schedules remain due (no
     * {@code nextExecutionAt} advance) and the next minute-tick re-picks them.
     * Optional autowiring keeps existing test fixtures green.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.lifecycle.OrchestratorLifecycleGate lifecycleGate;

    /**
     * In-memory guard: tracks schedule IDs currently being executed.
     * Prevents the same schedule from firing twice if a daemon tick overlaps with
     * a still-running execution from the previous tick.
     */
    private final ConcurrentHashMap<UUID, Boolean> executingScheduleIds = new ConcurrentHashMap<>();

    /**
     * Maximum spread window (ms) for cron-storm jitter. When more than one schedule
     * is due at the same tick, executions are spread evenly across this window
     * instead of all firing simultaneously. Prevents the lc-orchestrator OOM
     * 2026-05-06 12:22 pattern (87 schedules collapsed onto a single tick →
     * simultaneous JSONB read-modify-write storm).
     *
     * <p>Default 20 000 ms (20 s): MUST stay strictly less than
     * {@code @SchedulerLock(lockAtLeastFor = "PT30S")} on this same method so the
     * last deferred dispatch starts well before the ShedLock releases on this
     * replica. If the spread bleeds past lock release, replica B's next-minute
     * tick can re-dispatch the same schedule (its in-memory
     * {@code executingScheduleIds} guard is per-JVM, not cross-replica).
     * Configurable via {@code schedule.spread.max-ms}.
     */
    @Value("${schedule.spread.max-ms:20000}")
    private long spreadMaxMs;

    /**
     * Heap-pressure backpressure threshold (post-audit move 2026-05-06): when heap
     * utilization exceeds this fraction at the start of a tick, the WHOLE tick is
     * skipped - no schedule is dispatched, none has its {@code nextExecutionAt}
     * advanced via {@code advanceSchedule}, so the cron daemon at the NEXT minute
     * tick re-picks all of them via {@code findDueSchedules(now)}. Schedules are
     * eventually consistent - losing one minute-tick is acceptable.
     *
     * <p><b>Critical correctness note</b>: this check MUST happen BEFORE the per-
     * schedule dispatch loop so {@code advanceSchedule} (called inside
     * {@code executeWorkflowSchedule}) doesn't run. Earlier draft had the check in
     * {@code ExecutionQueueService.enqueueAndWait} which fires AFTER
     * {@code advanceSchedule} → deferred schedules were silently lost for up to a
     * full cron interval (audit 2026-05-06 P0 #1).
     *
     * <p><b>Measured against the POST-GC live set, not instantaneous heap.</b> The
     * fraction compared here is the post-GC working set (see {@link #isHeapUnderPressure()}),
     * because instantaneous {@code getHeapMemoryUsage().getUsed()} is the TOP of the G1
     * allocation sawtooth (live set + Eden garbage not yet collected) and routinely
     * sits above this threshold on a perfectly healthy JVM. Prod telemetry 2026-07-01
     * (orchestrator, Xmx 1536m): instantaneous heap peaked at 99.6% of Xmx over 7 days
     * while the post-GC live set never exceeded 40.3% and GC overhead peaked at 0.75%,
     * with zero OOMKills or restarts - yet the old instantaneous gate skipped ~62 cron
     * ticks/day for no real memory reason. The live set is what actually predicts Xmx
     * exhaustion (the May 2026 OOMs genuinely drove it toward the ceiling), so it is the
     * correct signal to back off on.
     *
     * <p>Default 0.85 (85%). Configurable via
     * {@code schedule.heap-pressure-threshold}.
     */
    @Value("${schedule.heap-pressure-threshold:0.85}")
    private double heapPressureThreshold;

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    /**
     * Seam over the two JVM readings the heap-pressure gate needs: the POST-GC live
     * heap set (numerator) and the heap max / Xmx (denominator). Production reads the
     * MXBeans; tests inject deterministic values. {@code volatile} so a test override
     * is safely published. See {@link #isHeapUnderPressure()} for why post-GC.
     */
    interface HeapUsageReader {
        /** Best available estimate of the live working set (post-GC where the collector exposes it). */
        long liveSetBytes();
        /** Maximum heap size (Xmx). */
        long maxBytes();
    }

    private volatile HeapUsageReader heapUsageReader = new MxBeanHeapUsageReader(
            ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(p -> p.getType() == MemoryType.HEAP)
                    .toList(),
            memoryMXBean);

    /**
     * Production {@link HeapUsageReader}: live set = the POST-GC ("collection") usage
     * summed across the heap pools, with an INSTANTANEOUS fallback when the running
     * collector does not expose post-GC usage.
     *
     * <p><b>Why the fallback.</b> {@code getUsed()} is defined under every collector,
     * but {@code getCollectionUsage()} is per-pool and may be absent (returns
     * {@code null}) - notably the CE monolith ships generational ZGC
     * ({@code -XX:+UseZGC -XX:+ZGenerational}, see monolith Dockerfile), whose pools do
     * not reliably record discrete post-collection points. Without a fallback, an
     * empty post-GC sum would read as 0 and the OOM backpressure gate - the whole point
     * of this class - would be silently disabled on the most memory-constrained
     * deployment. When no pool reports collection usage we degrade to the instantaneous
     * heap (the pre-2026-07 behaviour): conservative (may over-skip), never unsafe.
     * On G1 (prod orchestrator) collection usage is always present, so the fallback is
     * never taken there.
     */
    static final class MxBeanHeapUsageReader implements HeapUsageReader {
        private final List<MemoryPoolMXBean> heapPools;
        private final MemoryMXBean memoryMXBean;

        MxBeanHeapUsageReader(List<MemoryPoolMXBean> heapPools, MemoryMXBean memoryMXBean) {
            this.heapPools = heapPools;
            this.memoryMXBean = memoryMXBean;
        }

        @Override
        public long liveSetBytes() {
            long postGc = sumPostGcHeapUsed(heapPools).orElse(0L);
            // A POSITIVE post-GC reading is the true live set - use it. A 0 / absent
            // reading means the running collector does not expose usable post-collection
            // usage (e.g. generational ZGC on the CE monolith); degrade to the
            // instantaneous heap so the OOM gate stays armed rather than reading 0% and
            // never firing. A running orchestrator's live set is always well above 0, so
            // this only trips on genuinely-no-data, never on a healthy low live set.
            return postGc > 0L ? postGc : memoryMXBean.getHeapMemoryUsage().getUsed();
        }

        @Override
        public long maxBytes() {
            return memoryMXBean.getHeapMemoryUsage().getMax();
        }
    }

    /** Cumulative count of cron ticks SKIPPED under heap pressure (entire tick). */
    private final AtomicLong ticksSkippedUnderPressure = new AtomicLong(0);

    // Permanently zero after heap-check reorder (Phase 0): the heap gate now runs
    // BEFORE claimAndAdvanceDueSchedules, so no schedule count is available when
    // skipping. Kept for Prometheus metric registry stability (alert-rules.yml
    // references workflow_schedules_deferred_backpressure_total). Use
    // workflow_schedule_ticks_skipped_total instead.
    private final AtomicLong schedulesDeferredUnderPressure = new AtomicLong(0);

    /**
     * Off-the-@Scheduled-thread pool that runs the deferred {@code executeSchedule}
     * calls. Single thread is sufficient because each {@code executeSchedule}
     * dispatches to {@link com.apimarketplace.orchestrator.trigger.queue.ExecutionQueueService}
     * which has its own worker pool (20 threads) - the spread scheduler only does
     * the cheap dispatch + bookkeeping.
     */
    private ScheduledExecutorService spreadScheduler;

    /**
     * Strategy for dispatching deferred schedule executions. Production: defers
     * via {@link #spreadScheduler} after {@code delayMs}. Tests: substitutable
     * for synchronous same-thread execution so post-conditions assert directly.
     */
    @FunctionalInterface
    interface SpreadDispatcher {
        void dispatch(Runnable task, long delayMs);
    }

    /**
     * Default same-thread dispatcher. Spring lifecycle replaces it with the
     * production deferred dispatcher in {@link #initSpreadScheduler()}. Inlined
     * here (rather than null + late-init) so any pre-{@code @PostConstruct}
     * invocation of {@link #checkAndExecuteSchedules()} (theoretical race -
     * Spring runs scheduling after PostConstruct, but a future refactor could
     * break that) degrades to safe synchronous execution instead of NPE.
     */
    private volatile SpreadDispatcher spreadDispatcher = (task, delayMs) -> task.run();

    public ScheduleExecutorService(
            TriggerClient triggerClient,
            WorkflowRepository workflowRepository,
            WorkflowRunRepository runRepository,
            ReusableTriggerService triggerService,
            ProductionRunResolver productionRunResolver,
            AgentClient agentClient,
            ConversationClient conversationServiceClient,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.triggerClient = triggerClient;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.triggerService = triggerService;
        this.productionRunResolver = productionRunResolver;
        this.agentClient = agentClient;
        this.conversationServiceClient = conversationServiceClient;

        // Expose live concurrency for the cron storm pattern (HH:00 fan-out). Alert
        // threshold lives in deploy/config/alert-rules.yml - currently 30 active
        // schedules in any 10s window flags the storm. Required after the OOM
        // 2026-05-06 12:22 incident, where 87 schedules collapsed onto a single tick.
        if (meterRegistry != null) {
            Gauge.builder("workflow_trigger_fire_concurrency",
                          executingScheduleIds, ConcurrentHashMap::size)
                 .description("Number of schedule executions currently in flight")
                 .tag("type", "SCHEDULE")
                 .register(meterRegistry);
            // FunctionCounter (NOT Gauge) so Prometheus rate() works correctly across
            // JVM restarts - Gauge resets to 0, rate() goes negative, gets clamped to 0,
            // alerts silently fail across the exact crash-loop window operators care
            // about. Audit 2026-05-06 P0 #5 (Audit 4).
            FunctionCounter.builder("workflow_schedules_deferred_backpressure_total",
                                    schedulesDeferredUnderPressure,
                                    AtomicLong::get)
                 .description("Cumulative count of SCHEDULE fires deferred under heap pressure (whole-tick skip)")
                 .register(meterRegistry);
            FunctionCounter.builder("workflow_schedule_ticks_skipped_total",
                                    ticksSkippedUnderPressure,
                                    AtomicLong::get)
                 .description("Cumulative count of cron ticks fully skipped under heap pressure")
                 .register(meterRegistry);
        }
    }

    /**
     * Reads JVM heap pressure as the POST-GC live working set over Xmx, returning
     * true when it exceeds the configured backpressure threshold.
     *
     * <p>Deliberately NOT {@code getHeapMemoryUsage().getUsed() / getMax()}: that
     * instantaneous ratio is the top of the G1 allocation sawtooth (live set + Eden
     * garbage the collector has not reclaimed yet) and routinely reads >85% on a
     * healthy JVM whose true working set is a fraction of that. Prod telemetry
     * 2026-07-01 confirmed the divergence (instantaneous peak 99.6% vs post-GC live
     * peak 40.3%, GC overhead peak 0.75%, zero OOMKills) - the old signal skipped
     * ~62 cron ticks/day for no real memory reason. The post-GC live set (sum of the
     * heap pools' {@code getCollectionUsage()}, closely related to Micrometer's
     * {@code jvm_gc_live_data_size_bytes}) is what actually predicts Xmx exhaustion,
     * so it is the correct thing to back off on. It trips slightly later than the old
     * garbage-inclusive signal, but at Xmx 1536m the 15% headroom above the 85% live
     * threshold spans several minutes of real fan-out (the May 2026 OOMs ramped over
     * ~7 min), which the once-per-minute tick observes.
     *
     * <p>Package-private to support deterministic test mocking via Mockito spy
     * without stubbing the MemoryMXBean singleton.
     */
    boolean isHeapUnderPressure() {
        HeapUsageReader reader = this.heapUsageReader; // read the volatile once
        long max = reader.maxBytes();
        if (max <= 0) return false;
        return ((double) reader.liveSetBytes() / (double) max) > heapPressureThreshold;
    }

    /**
     * Sum of post-GC ("collection") usage across the given heap pools = the live
     * working set after the JVM's most recent collection of each pool.
     *
     * <p>Returns {@link OptionalLong#empty()} when NO pool exposes a collection usage
     * (every {@code getCollectionUsage()} is {@code null}) - i.e. the running collector
     * does not record discrete post-collection points. Callers must then fall back to
     * an instantaneous reading rather than treat "no data" as "0 bytes live", otherwise
     * the backpressure gate silently disables itself (see {@link MxBeanHeapUsageReader}).
     * Pools that individually return {@code null} contribute 0 to a sum that is still
     * present as long as at least one pool reports. Standard collectors are all-or-nothing
     * across heap pools (G1: all report; ZGC: none), so a partial report is a JVM anomaly;
     * even then the dangerous sub-case (the pressure-bearing Old Gen not reporting) yields a
     * sum near 0 and the reader's positive-only guard falls back to the instantaneous heap.
     *
     * <p>Static + package-private so a unit test can prove it reads
     * {@code getCollectionUsage()} (post-GC) and never {@code getUsage()} (instantaneous).
     */
    static OptionalLong sumPostGcHeapUsed(List<MemoryPoolMXBean> pools) {
        long used = 0L;
        boolean anyReported = false;
        for (MemoryPoolMXBean pool : pools) {
            MemoryUsage collectionUsage = pool.getCollectionUsage();
            if (collectionUsage != null) {
                anyReported = true;
                used += collectionUsage.getUsed();
            }
        }
        return anyReported ? OptionalLong.of(used) : OptionalLong.empty();
    }

    /** Test seam - override the heap readings with deterministic values. */
    void setHeapUsageReaderForTesting(HeapUsageReader reader) {
        this.heapUsageReader = reader;
    }

    /** Post-GC live-set percentage of Xmx, rounded to 0.1%, for the skip log line. */
    private double currentHeapPercent() {
        HeapUsageReader reader = this.heapUsageReader; // read the volatile once
        long max = reader.maxBytes();
        if (max <= 0) return -1.0;
        return Math.round((double) reader.liveSetBytes() / max * 1000.0) / 10.0;
    }

    /** Test/observability hook - total schedules deferred since boot. */
    public long getSchedulesDeferredUnderPressure() {
        return schedulesDeferredUnderPressure.get();
    }

    /** Test/observability hook - total ticks skipped since boot. */
    public long getTicksSkippedUnderPressure() {
        return ticksSkippedUnderPressure.get();
    }

    @PostConstruct
    void initSpreadScheduler() {
        if (spreadScheduler == null) {
            spreadScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "schedule-spread");
                t.setDaemon(true);
                return t;
            });
        }
        // Replace the safe-default same-thread dispatcher with the production
        // deferred-dispatch implementation. volatile write is safely published.
        spreadDispatcher = (task, delayMs) ->
            spreadScheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Test-only - replace the spread dispatcher with a synchronous same-thread
     * implementation so {@code checkAndExecuteSchedules} runs each
     * {@code executeSchedule} inline. NEVER call from production code.
     */
    void setSpreadDispatcherForTesting(SpreadDispatcher dispatcher) {
        this.spreadDispatcher = dispatcher;
    }

    void setExecutionQueueForTesting(ExecutionQueue executionQueue) {
        this.executionQueue = executionQueue;
    }

    @PreDestroy
    void shutdownSpreadScheduler() {
        if (spreadScheduler != null) {
            spreadScheduler.shutdown();
            try {
                if (!spreadScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    spreadScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                spreadScheduler.shutdownNow();
            }
        }
    }


    /**
     * Main daemon - runs every minute.
     * Single query to find all due schedules via trigger-service.
     *
     * <p><b>Cron-storm jitter</b>: when more than one schedule is due at this tick,
     * executions are dispatched onto {@link #spreadScheduler} with a calculated
     * delay so the {@code N} schedules are spread evenly across the
     * {@code spreadMaxMs} window (default 20 s, configurable via
     * {@code schedule.spread.max-ms}) instead of all firing simultaneously.
     * Prevents the {@code lc-orchestrator} OOM 2026-05-06 12:22 pattern: 87
     * schedules at HH:00 → simultaneous JSONB read-modify-write storm → heap
     * exhaustion in 8 min. Post-fix: same 87 schedules spread over 20 s ≈
     * 4 fires/s, matches the {@code WorkflowScheduleStorm} alert ceiling and
     * gives G1GC + the per-tx parse cache time to absorb each batch.
     *
     * <p>Bookkeeping note: {@code executingScheduleIds} is set IMMEDIATELY in the
     * for loop (synchronously) and removed only after the deferred task completes.
     * The next tick at HH:01 will skip any still-running schedule, preserving the
     * "no double-fire" invariant under the spread.
     */
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "scheduleExecutor_poll", lockAtMostFor = "PT55S", lockAtLeastFor = "PT30S")
    public void checkAndExecuteSchedules() {
        // Lifecycle gate (post-2026-05-22 OOM hardening): refuse to fire schedules while
        // the instance is WARMING (just-restarted, in-flight replay still landing) or
        // DRAINING (planned shutdown). Schedules remain due - the next minute-tick will
        // re-pick them. Documents the same eventual-consistency contract as the heap-
        // pressure backpressure check below.
        if (lifecycleGate != null && (lifecycleGate.isWarming() || lifecycleGate.isDraining())) {
            logger.info("[Schedule] Skipping tick - instance in {} state. Schedules remain due; next minute-tick will re-pick.",
                lifecycleGate.currentState());
            return;
        }

        // Heap-pressure gate BEFORE claiming schedules. claimAndAdvanceDueSchedules
        // atomically advances nextExecutionAt in the trigger-service transaction -
        // if we claimed first and then skipped due to heap pressure, the advanced
        // schedules would be lost until their next cron occurrence. Checking here
        // means no claim happens, schedules remain due, and the next minute-tick
        // re-picks them.
        if (isHeapUnderPressure()) {
            ticksSkippedUnderPressure.incrementAndGet();
            logger.warn("[Schedule] Skipping ENTIRE tick due to heap pressure (post-GC live set {}% of Xmx) - no schedules claimed, will retry at next minute-tick",
                currentHeapPercent());
            return;
        }

        Instant now = Instant.now();
        List<ScheduledExecutionDto> dueSchedules = triggerClient.claimAndAdvanceDueSchedules(now);

        if (dueSchedules == null || dueSchedules.isEmpty()) {
            logger.debug("[Schedule] No due schedules");
            return;
        }

        int total = dueSchedules.size();

        // Compute the inter-schedule spread interval. With N schedules and a
        // spreadMaxMs window, place schedule i at offset (i * spreadMaxMs / N).
        // Single schedule → no spread (delay 0). Two schedules → fire one at 0,
        // the next at spreadMaxMs/2.
        long perScheduleSpreadMs = total > 1 ? spreadMaxMs / total : 0L;

        logger.info("[Schedule] Found {} due schedule(s); spreading over {} ms (≈{} ms apart)",
                total, total > 1 ? spreadMaxMs : 0, perScheduleSpreadMs);

        for (int idx = 0; idx < total; idx++) {
            ScheduledExecutionDto schedule = dueSchedules.get(idx);
            UUID scheduleId = schedule.getId();

            // In-memory guard: skip if this schedule is already being executed.
            // Set immediately (synchronously) so the next minute-tick treats this
            // schedule as in-flight even if its deferred task hasn't started yet.
            if (executingScheduleIds.putIfAbsent(scheduleId, Boolean.TRUE) != null) {
                logger.info("[Schedule] Skipping schedule {} - already executing from a previous tick",
                        scheduleId);
                continue;
            }

            // Calculated delay + small randomized jitter (≤500 ms) so schedules
            // sharing the same cron expression do not always fire in the same order.
            // Jitter is gated on perScheduleSpreadMs > 0 so spreadMaxMs=0 ("no
            // spread, fire synchronously") is honoured exactly - the operator
            // explicitly opting out of spread should not get a silent 0-500 ms
            // randomization.
            long delayMs = idx * perScheduleSpreadMs;
            if (perScheduleSpreadMs > 0) {
                delayMs += ThreadLocalRandom.current().nextLong(0, 500);
            }

            try {
                spreadDispatcher.dispatch(() -> runScheduleSafely(schedule, scheduleId), delayMs);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Dispatcher pool rejected (after @PreDestroy shutdown or in-flight
                // reconfiguration). Free the in-memory guard so the next tick can
                // re-pick this schedule cleanly. Audit 2026-05-06 round 2 P2 #6:
                // narrow from RuntimeException so genuine bugs in dispatch (NPE,
                // ClassCastException) propagate up to the operator instead of being
                // silently swallowed as "rejected".
                executingScheduleIds.remove(scheduleId);
                logger.warn("[Schedule] Dispatcher rejected schedule {}: {} - will retry at next tick",
                        scheduleId, e.getMessage());
            } catch (RuntimeException e) {
                // Audit 2026-05-06 round 3 P2 #4: a genuine bug in dispatch (NPE,
                // ClassCastException) MUST propagate, but if we let the exception
                // unwind the for-loop without freeing executingScheduleIds, this
                // schedule is locked out of every subsequent tick until JVM restart
                // ("already executing from a previous tick" guard). Free the guard
                // FIRST, then re-throw so the operator sees the real exception via
                // the @Scheduled framework's logging.
                //
                // Trade-off: re-throwing aborts the for-loop, so any due schedules
                // queued AFTER this one in the same tick are deferred to the next
                // 1-minute scheduler tick. Acceptable - the deferred schedules are
                // re-discovered by findDueSchedules() and the executingScheduleIds
                // guard for them was never set, so no leak.
                executingScheduleIds.remove(scheduleId);
                throw e;
            }
        }
    }

    /**
     * Wrapper that runs {@code executeSchedule} with the existing error-handling
     * + executingScheduleIds-cleanup contract. Extracted for the deferred-dispatch
     * path so the cron-storm jitter can call into it from the spread scheduler.
     */
    private void runScheduleSafely(ScheduledExecutionDto schedule, UUID scheduleId) {
        try {
            executeSchedule(schedule);
        } catch (Exception e) {
            logger.error("[Schedule] Error executing schedule {}: {}",
                    scheduleId, e.getMessage(), e);
        } finally {
            executingScheduleIds.remove(scheduleId);
        }
    }

    /**
     * Execute a single schedule.
     * Accumulation pattern: finds existing run in WAITING_TRIGGER and triggers it.
     * If no run found, disables the schedule (user must start a new run from UI).
     *
     * <p><b>Optimistic advance</b>: {@code recordScheduleExecution} is called BEFORE
     * the workflow executes. This guarantees that even if execution is slow or throws,
     * the next daemon tick will not re-pick this schedule (because {@code nextExecutionAt}
     * has already been advanced to the next cron slot).</p>
     */
    public void executeSchedule(ScheduledExecutionDto schedule) {
        // Post-V261 - bind the schedule's organization_id to the thread-local
        // so any USER_SCOPED entity persisted during dispatch (workflow_runs,
        // agent_executions, notifications…) inherits the workspace via
        // OrgScopedEntityListener. Without this, daemon-driven inserts would
        // see no servlet request, leave org_id null, and hit the V261 NOT NULL
        // constraint.
        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
                schedule.getOrganizationId(),
                () -> {
                    if (schedule.getAgentEntityId() != null) {
                        executeAgentSchedule(schedule);
                    } else {
                        executeWorkflowSchedule(schedule);
                    }
                });
    }

    /**
     * Execute a workflow schedule.
     * Finds existing run in WAITING_TRIGGER and triggers it.
     */
    private void executeWorkflowSchedule(ScheduledExecutionDto schedule) {
        // Phase 1: prepare - resolve run, validate state
        ScheduleRunInfo info = prepareScheduleExecution(schedule);
        if (info == null) {
            return; // no run found or schedule disabled
        }

        // PR22 R2 - workspace-scope guard. Mirror WebhookDispatchService.dispatchStandalone:
        // schedule is tagged for a workspace, pinned WAITING_TRIGGER run is tagged for a
        // workspace (PR15 V209). A schedule MUST NOT fire a run in a different workspace,
        // even if both share the same tenant and workflow id.
        // NOTE: The PR22 commit message earlier claimed the daemon "stamps org_id onto
        // the created workflow_run" - that was inaccurate. Schedules fire EXISTING
        // WAITING_TRIGGER runs (no new run is created). The correct semantic is: refuse
        // to fire if the existing run's scope doesn't match the schedule's.
        String scheduleOrg = schedule.getOrganizationId();
        String runOrg = info.run().getOrgId();
        if (!com.apimarketplace.common.scope.ScopeGuard.crossResourceMatches(scheduleOrg, runOrg)) {
            logger.info("Skipping schedule fire for workflow {} - workspace mismatch "
                + "(schedule org={}, run org={})", schedule.getWorkflowId(), scheduleOrg, runOrg);
            return;
        }

        // Phase 2: check queue readiness before the schedule is advanced.
        // Redis mode must leave the cron occurrence due when no worker can accept it.
        if (!isExecutionQueueReadyForSchedule(schedule.getId())) {
            return;
        }

        // Phase 3: execute. The schedule was already advanced by claimAndAdvanceDueSchedules
        // (FOR UPDATE SKIP LOCKED), so another pod cannot re-pick the same slot.
        // The deterministic requestId still provides defense-in-depth via the claim store.
        Map<String, Object> payload = com.apimarketplace.orchestrator.trigger
                .ReusableTriggerService.sanitizePlanMarker(buildPayload(schedule, schedule.getNextExecutionAt()));
        String requestId = ReusableTriggerService.deterministicScheduleRequestId(
                schedule.getId(), schedule.getNextExecutionAt());
        TriggerExecutionResult result;
        try {
            result = ExecutionQueueRequestContext.runWith(requestId, () ->
                    triggerService.executeTrigger(info.run(), info.triggerId(), TriggerType.SCHEDULE, payload));
        } catch (RuntimeException e) {
            // Schedule already advanced - no need to re-advance on exception.
            throw e;
        }
        if (isExecutionQueueUnavailableResult(result)) {
            // Queue unavailable: restore the schedule so it fires on the next tick.
            restoreScheduleDispatchIfQueueUnavailable(schedule, null, result);
        }

        // Phase 4: log outcome
        logExecutionResult(schedule.getWorkflowId(), info.run().getRunIdPublic(), result);
    }

    /**
     * Execute an agent schedule via conversation-service (same pipeline as chat/webhook).
     * Creates a conversation, sends the schedulePrompt as a user message, and lets
     * conversation-service handle history, tools, observability, and persistence.
     *
     * When withMemory is true (schedule field), reuses the agent's existing conversation
     * so the agent has access to prior messages. Otherwise creates a fresh conversation.
     */
    private void executeAgentSchedule(ScheduledExecutionDto schedule) {
        UUID agentEntityId = schedule.getAgentEntityId();
        String tenantId = schedule.getTenantId();
        String staticPrompt = schedule.getSchedulePrompt();
        boolean withMemory = Boolean.TRUE.equals(schedule.getWithMemory());

        logger.info("[Schedule] Preparing agent execution for agent {} (schedule: {}, tenant: {}, withMemory: {})",
                agentEntityId, schedule.getId(), tenantId, withMemory);

        // Phase 1: build the effective prompt. Task delegation REPLACES the static
        // schedulePrompt with a dynamic task-inbox prompt when the agent has pending
        // work. If there is no work, agent-service returns the static fallback unchanged.
        // Any RPC failure falls back to the static prompt (safe: logged + continues).
        // PR26 - thread schedule.organizationId explicitly because this method runs on
        // a daemon thread (no RequestContextHolder for the PR16 forwarder to read from).
        // Pre-PR26 the agent received a tenant-wide task list, bleeding org tasks into
        // personal-scope prompts (and vice versa).
        String prompt = agentClient.buildScheduledPrompt(
                agentEntityId, tenantId, schedule.getOrganizationId(), staticPrompt);
        if (prompt == null || prompt.isBlank()) {
            logger.warn("[Schedule] Agent schedule {} has no effective prompt (no tasks and no static schedulePrompt), skipping",
                    schedule.getId());
            return;
        }

        // Post-V261 - daemon thread has no RequestContextHolder, so the
        // 2-arg legacy AgentClient.getAgent(id, tenantId) cannot resolve the
        // schedule's workspace from a thread-local. Pass the schedule's
        // captured organizationId explicitly so server-side strict isolation
        // matches the row's organization_id.
        AgentDto agent = agentClient.getAgent(agentEntityId, tenantId, schedule.getOrganizationId());
        if (agent == null) {
            logger.warn("[Schedule] Agent {} not found in scope (org={}), disabling schedule {}",
                    agentEntityId, schedule.getOrganizationId(), schedule.getId());
            triggerClient.disableSchedule(schedule.getId());
            return;
        }

        if (Boolean.FALSE.equals(agent.getIsActive())) {
            logger.info("[Schedule] Agent {} is inactive, skipping schedule {}", agentEntityId, schedule.getId());
            return;
        }

        // PR22c R3 - workspace-scope guard for agent schedules. Mirror the workflow-schedule
        // guard at executeWorkflowSchedule. The schedule is tagged for a workspace (PR22 R2.0
        // toDto carries orgId); the agent is tagged for a workspace (PR23 agent runtime).
        // A schedule MUST NOT fire an agent in a different workspace, even if the tenant
        // matches and the schedule was created when the agent was in a different scope.
        // Convergent R2 must-fix A+C - without this, the executeWorkflowSchedule guard at
        // line ~437 only covers half the dispatch surface.
        String scheduleOrg = schedule.getOrganizationId();
        String agentOrg = agent.getOrganizationId();
        if (!com.apimarketplace.common.scope.ScopeGuard.crossResourceMatches(scheduleOrg, agentOrg)) {
            logger.info("[Schedule] Skipping agent schedule fire for agent {} - workspace mismatch "
                + "(schedule org={}, agent org={})", agentEntityId, scheduleOrg, agentOrg);
            return;
        }

        // Phase 2: optimistic advance
        ScheduledExecutionDto recordedSchedule = advanceSchedule(schedule.getId());
        if (isRecordedScheduleInactive(recordedSchedule)) {
            logger.info("[Schedule] Skipping agent schedule {} after trigger-service disabled or archived it",
                    schedule.getId());
            return;
        }

        // Phase 3: create or reuse conversation, then send message via conversation-service
        try {
            String model = agent.getModelName();
            String provider = agent.getModelProvider();

            // Resolve conversation ID - same pattern as AgentNode.ensureConversation
            // Audit 2026-05-17 round-5 - daemon path threads schedule's org so the
            // created/found conversation + the credit consumption land in the
            // schedule's workspace, not personal.
            String scheduleOrgId = schedule.getOrganizationId();
            // Always reuse the agent's primary conversation, regardless of withMemory.
            // The previous design created a fresh conversation per fire when withMemory=false,
            // which polluted the sidebar (one orphan conv per cron tick) and prevented the
            // agent from having a single home for all its scheduled activity. The withMemory
            // flag's only legitimate effect is whether prior messages are loaded into the
            // agent's prompt context - handled at chat-time by conversation-service, not by
            // routing to a different conversation row here.
            String conversationId = conversationServiceClient.findOrCreateAgentConversation(
                    agentEntityId.toString(), tenantId, agent.getName(), scheduleOrgId);
            if (conversationId == null) {
                logger.error("[Schedule] Failed to find/create agent conversation for {}", agentEntityId);
                return;
            }
            // TODO: wire withMemory to chat-time history loading in conversation-service.
            // Today executeSync never loads conversation history, so withMemory is inert
            // on the sync path. Keeping it in the log so operators can see the schedule's
            // intent in surveys without grepping the DB.
            logger.info("[Schedule] Using agent conversation {} (withMemory={}, history loading TBD)",
                    conversationId, withMemory);

            // Execute synchronously via shared conversation client.
            // Audit 2026-05-17 round-5 - thread scheduleOrgId so chat-side credit
            // consumption hits the schedule's org wallet, not personal.
            var result = conversationServiceClient.sendChatSync(tenantId, conversationId, prompt,
                    agentEntityId.toString(), model, provider, "SCHEDULE", null, scheduleOrgId);

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                logger.info("[Schedule] Agent {} executed successfully (schedule: {}, conversation: {})",
                        agentEntityId, schedule.getId(), conversationId);
            } else {
                logger.error("[Schedule] Agent {} execution failed (schedule: {}): {}",
                        agentEntityId, schedule.getId(), result.get("error"));
            }
        } catch (Exception e) {
            logger.error("[Schedule] Agent {} execution threw exception (schedule: {}): {}",
                    agentEntityId, schedule.getId(), e.getMessage(), e);
        }
    }

    /**
     * Prepare phase: find the WAITING_TRIGGER run for this schedule.
     *
     * <p>PR1 (round-7 redesign): the resolver is now invoked with
     * {@link ProductionRunResolver.RunSelectionPolicy#LATEST_WAITING_TRIGGER}, which
     * filters to WAITING_TRIGGER runs only. This eliminates the prod incident class
     * where a CANCELLED run shadowed a valid older WAITING_TRIGGER run and caused the
     * schedule to be permanently auto-disabled.
     *
     * <p>The dispatch layer NO LONGER auto-disables triggers on missing-run conditions.
     * It logs and skips the tick - the trigger lifecycle is owned by pin/admin/reaper,
     * not by the dispatch hot path. {@code triggerClient.disableSchedule(...)} calls
     * have been removed accordingly.
     */
    private ScheduleRunInfo prepareScheduleExecution(ScheduledExecutionDto schedule) {
        String triggerId = schedule.getTriggerId();
        java.util.UUID workflowId = schedule.getWorkflowId();

        // Standalone schedules not linked to a workflow cannot execute
        if (workflowId == null) {
            logger.debug("[Schedule] Skipping standalone schedule {} - no workflow linked", schedule.getId());
            return null;
        }

        logger.info("[Schedule] Preparing execution for workflow {} trigger {} (schedule: {})",
                workflowId, triggerId, schedule.getId());

        // Schedule's accumulation pattern requires a run in WAITING_TRIGGER status
        // at the workflow's pinned version. Other statuses are skipped (logged, no
        // state mutation - the schedule stays armed for the next tick).
        // PR4: policy comes from the strategy registered in TriggerDispatchCoordinator
        // when wired; otherwise falls back to the literal policy (test-only path).
        ProductionRunResolver.RunSelectionPolicy policy = dispatchCoordinator != null
                ? dispatchCoordinator.policyFor(TriggerType.SCHEDULE)
                : ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER;
        ProductionRunResolver.Resolution resolution = productionRunResolver.resolve(workflowId, policy);
        if (!resolution.isFound()) {
            // SBS fallback: the strict LATEST_WAITING_TRIGGER policy only finds a run in
            // WAITING_TRIGGER, but a STEP_BY_STEP run rests in PAUSED/RUNNING/AWAITING_SIGNAL
            // while the user steps (reconcileSbsRunStatus parks it there) and only reaches
            // WAITING_TRIGGER between fully-completed epochs. Without this, a scheduled SBS
            // workflow never gets a new epoch mid-debug (the tick is silently skipped).
            // Mirror the webhook/manual path: fire the pinned SBS run so executeTriggerInternal's
            // SBS branch closes the open epoch and opens a fresh one (one new epoch per tick).
            // resolveStepByStepRun() is gated on isStepByStepMode(), so AUTOMATIC PAUSED/RUNNING
            // runs keep the strict WAITING_TRIGGER contract unchanged. Scoped to NO_PRODUCTION_RUN
            // (pinned but no WAITING_TRIGGER run) so unpinned/missing ticks pay no extra query.
            if (resolution.isNoProductionRun()) {
                Optional<WorkflowRunEntity> sbsRun = productionRunResolver.resolveStepByStepRun(workflowId);
                if (sbsRun.isPresent()) {
                    if (dispatchCoordinator != null) {
                        dispatchCoordinator.recordVerdict(TriggerType.SCHEDULE, DispatchVerdict.FIRE);
                    }
                    logger.info("[Schedule] Firing step-by-step run {} for workflow {} (status={}) - " +
                            "no WAITING_TRIGGER run; SBS rests in PAUSED/RUNNING, so a fresh epoch is " +
                            "opened this tick.",
                            sbsRun.get().getRunIdPublic(), workflowId, sbsRun.get().getStatus());
                    // Return EARLY, deliberately bypassing the runStatus != WAITING_TRIGGER
                    // "REFUSE_RUN_TERMINAL" guard below: an SBS run is intentionally PAUSED/RUNNING
                    // here, and executeTriggerInternal's SBS branch opens the fresh epoch. The
                    // workspace-scope guard in executeWorkflowSchedule still applies to this run.
                    return new ScheduleRunInfo(sbsRun.get(), triggerId);
                }
            }
            // PR4: emit unified verdict for observability (Prometheus
            // trigger_dispatch_total{trigger_type, verdict}).
            if (dispatchCoordinator != null) {
                dispatchCoordinator.recordVerdict(
                    TriggerType.SCHEDULE,
                    TriggerExecutionStrategy.defaultVerdictFor(resolution.outcome()));
            }
            switch (resolution.outcome()) {
                case NOT_PINNED -> logger.debug(
                    // DEBUG - this fires on every cron tick for every unpinned workflow.
                    // Verdict is recorded in trigger_dispatch_total{verdict=REFUSE_NOT_PINNED}
                    // Prometheus counter by the dispatchCoordinator above; the UI also shows
                    // unpinned state on the schedule row. Log entry is pure noise (~11/min
                    // in prod) and was flagged as 11% of orchestrator log volume.
                    "[Schedule] Workflow {} has no pinned version - schedule {} skipped this tick. " +
                    "Pin a production version to enable automatic execution.",
                    workflowId, schedule.getId());
                case NO_PRODUCTION_RUN -> logger.warn(
                    "[Schedule] Workflow {} pinned but no WAITING_TRIGGER run at that version - " +
                    "schedule {} skipped this tick (will retry next tick).",
                    workflowId, schedule.getId());
                case WORKFLOW_MISSING -> logger.warn(
                    "[Schedule] Workflow {} not found - schedule {} skipped this tick.",
                    workflowId, schedule.getId());
                default -> { /* nothing */ }
            }
            return null;
        }

        WorkflowRunEntity run = resolution.run().get();

        // Defensive guard: the resolver should already filter terminal statuses, but
        // a race between resolver SELECT and dispatch could surface one. Skip rather
        // than disable - the next tick will re-resolve cleanly.
        RunStatus runStatus = run.getStatus();
        if (runStatus != RunStatus.WAITING_TRIGGER) {
            if (dispatchCoordinator != null) {
                dispatchCoordinator.recordVerdict(TriggerType.SCHEDULE,
                    DispatchVerdict.REFUSE_RUN_TERMINAL);
            }
            logger.warn("[Schedule] Run {} for workflow {} is not WAITING_TRIGGER ({}), " +
                    "schedule {} skipped this tick.",
                    run.getRunIdPublic(), workflowId, runStatus, schedule.getId());
            return null;
        }
        if (dispatchCoordinator != null) {
            dispatchCoordinator.recordVerdict(TriggerType.SCHEDULE, DispatchVerdict.FIRE);
        }

        logger.info("[Schedule] Found waiting run {} for workflow {}",
                run.getRunIdPublic(), workflowId);
        return new ScheduleRunInfo(run, triggerId);
    }

    /**
     * Advance the schedule's {@code nextExecutionAt} to the next cron slot.
     * Workflow daemon execution calls this after queue acceptance so a queue outage
     * leaves the same occurrence due for retry.
     */
    private ScheduledExecutionDto advanceSchedule(UUID scheduleId) {
        try {
            return triggerClient.recordScheduleExecution(scheduleId);
        } catch (Exception e) {
            // Even if the HTTP call fails, we continue with execution.
            // The in-memory guard still protects against double-fire within this JVM.
            logger.warn("[Schedule] Failed to advance nextExecutionAt for schedule {}: {}",
                    scheduleId, e.getMessage());
            return null;
        }
    }

    private boolean isExecutionQueueReadyForSchedule(UUID scheduleId) {
        if (executionQueue == null || executionQueue.isReadyForEnqueue()) {
            return true;
        }
        logger.warn("[Schedule] Skipping schedule {} because execution queue is not ready; next tick will retry",
                scheduleId);
        return false;
    }

    private void restoreScheduleDispatchIfQueueUnavailable(
            ScheduledExecutionDto previousSchedule,
            ScheduledExecutionDto recordedSchedule,
            TriggerExecutionResult result) {
        if (!isExecutionQueueUnavailableResult(result) || recordedSchedule == null) {
            return;
        }
        int previousCount = previousSchedule != null ? previousSchedule.getExecutionCount() : 0;
        int advancedCount = recordedSchedule.getExecutionCount();
        triggerClient.restoreScheduleDispatch(
                recordedSchedule.getId(),
                previousSchedule != null ? previousSchedule.getNextExecutionAt() : null,
                previousSchedule != null ? previousSchedule.getLastExecutionAt() : null,
                recordedSchedule.getNextExecutionAt(),
                recordedSchedule.getLastExecutionAt(),
                previousCount,
                advancedCount);
    }

    private static boolean isExecutionQueueUnavailableResult(TriggerExecutionResult result) {
        return result != null
                && !result.success()
                && RedisExecutionQueueService.QUEUE_UNAVAILABLE_MESSAGE.equals(result.message());
    }

    private boolean isRecordedScheduleInactive(ScheduledExecutionDto recordedSchedule) {
        return recordedSchedule != null
                && (!recordedSchedule.isEnabled() || !recordedSchedule.getIsActive());
    }

    /**
     * Log execution outcome (success or failure).
     */
    private void logExecutionResult(UUID workflowId, String runIdPublic, TriggerExecutionResult result) {
        if (result.success()) {
            logger.info("[Schedule] Workflow {} executed (run: {})",
                    workflowId, runIdPublic);
        } else {
            logger.error("[Schedule] Workflow {} execution failed: {}",
                    workflowId, result.message());
        }
    }

    /**
     * Internal DTO for passing prepared schedule data between phases.
     */
    record ScheduleRunInfo(WorkflowRunEntity run, String triggerId) {}

    /**
     * Package-private: check if a schedule is currently executing (for testing).
     */
    boolean isScheduleExecuting(UUID scheduleId) {
        return executingScheduleIds.containsKey(scheduleId);
    }

    /**
     * Manual execution (bypass cron check).
     * For workflow schedules: finds existing run in WAITING_TRIGGER - never creates a new one.
     * For agent schedules: executes the agent immediately with the schedulePrompt.
     */
    public TriggerExecutionResult executeNow(ScheduledExecutionDto schedule) {
        if (schedule.getAgentEntityId() != null) {
            return executeAgentNow(schedule);
        }

        // Workflow schedule path
        // Phase 1: prepare
        ScheduleRunInfo info = prepareScheduleExecution(schedule);
        if (info == null) {
            return TriggerExecutionResult.failure(null, null, TriggerType.SCHEDULE,
                    "No active run found. Start a run from the workflow builder first.");
        }

        if (!isExecutionQueueReadyForSchedule(schedule.getId())) {
            return TriggerExecutionResult.failure(null, null, TriggerType.SCHEDULE,
                    "Execution queue unavailable, please retry");
        }

        // Phase 2: optimistic advance
        ScheduledExecutionDto recordedSchedule = advanceSchedule(schedule.getId());
        if (isRecordedScheduleInactive(recordedSchedule)) {
            return TriggerExecutionResult.failure(null, null, TriggerType.SCHEDULE,
                    "Schedule was disabled before execution.");
        }
        Instant nextExecutionAt = recordedSchedule != null ? recordedSchedule.getNextExecutionAt() : null;

        // Phase 3: execute (may block in queue). Sanitize for contract uniformity.
        Map<String, Object> payload = buildPayload(schedule, nextExecutionAt);
        payload.put("manual", true);
        payload = com.apimarketplace.orchestrator.trigger.ReusableTriggerService.sanitizePlanMarker(payload);
        TriggerExecutionResult result = triggerService.executeTrigger(
                info.run(), info.triggerId(), TriggerType.SCHEDULE, payload);
        restoreScheduleDispatchIfQueueUnavailable(schedule, recordedSchedule, result);

        // Phase 4: log outcome
        logExecutionResult(schedule.getWorkflowId(), info.run().getRunIdPublic(), result);

        return result;
    }

    /**
     * Manual execution for agent schedules.
     * Delegates to the same agent execution logic but wraps the result
     * as a TriggerExecutionResult for API compatibility.
     */
    private TriggerExecutionResult executeAgentNow(ScheduledExecutionDto schedule) {
        try {
            executeAgentSchedule(schedule);
            return TriggerExecutionResult.success(null, null, TriggerType.SCHEDULE, null, 0);
        } catch (Exception e) {
            return TriggerExecutionResult.failure(null, null, TriggerType.SCHEDULE,
                    "Agent execution failed: " + e.getMessage());
        }
    }

    /**
     * Build trigger payload with schedule metadata.
     */
    private Map<String, Object> buildPayload(ScheduledExecutionDto schedule) {
        return buildPayload(schedule, schedule.getNextExecutionAt());
    }

    private Map<String, Object> buildPayload(ScheduledExecutionDto schedule, Instant nextExecutionAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("triggeredAt", Instant.now().toString());
        payload.put("executionCount", schedule.getExecutionCount() + 1);
        payload.put("cron", schedule.getCronExpression());
        payload.put("timezone", schedule.getTimezone());
        payload.put("scheduleId", schedule.getId().toString());
        payload.put("triggerId", schedule.getTriggerId());
        if (nextExecutionAt != null) {
            payload.put("nextExecution", nextExecutionAt.toString());
        }
        return payload;
    }

    /**
     * TTL cleanup job - runs every hour.
     * Delegated to trigger-service.
     */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "scheduleExecutor_cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanupExpiredSchedules() {
        try {
            Integer cleaned = triggerClient.cleanupExpiredSchedules();
            if (cleaned != null && cleaned > 0) {
                logger.info("[Schedule] Cleaned up {} expired/completed schedule(s)", cleaned);
            }
        } catch (Exception e) {
            logger.error("[Schedule] Error in expired schedules cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Get schedule status for API.
     */
    public ScheduleStatusResponse getScheduleStatus(ScheduledExecutionDto schedule) {
        return new ScheduleStatusResponse(
                schedule.getCronExpression(),
                schedule.getTimezone(),
                schedule.isEnabled(),
                schedule.getLastExecutionAt(),
                schedule.getNextExecutionAt(),
                schedule.getExecutionCount(),
                schedule.hasReachedMaxExecutions() ? "completed" : (schedule.isEnabled() ? "active" : "paused"),
                schedule.getMaxExecutions()
        );
    }

    /**
     * Response DTO for schedule status.
     */
    public record ScheduleStatusResponse(
            String cron,
            String timezone,
            boolean enabled,
            Instant lastExecutionAt,
            Instant nextExecutionAt,
            int executionCount,
            String status,
            Integer maxExecutions
    ) {}
}
