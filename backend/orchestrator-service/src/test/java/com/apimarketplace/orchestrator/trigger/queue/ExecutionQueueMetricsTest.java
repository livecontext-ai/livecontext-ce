package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Publisher-layer test: verifies {@link ExecutionQueueMetrics} emits both an aggregate
 * series ({@code tenant="all"}) AND a per-tenant series for each active caller, under a
 * single {@code workflow_queue_*} metric name. Mirrors the multi-tenant contract that
 * {@code RateLimitMetrics} establishes for the LLM rate-limiter.
 */
class ExecutionQueueMetricsTest {

    private SimpleMeterRegistry registry;
    private ExecutionQueueMetrics metrics;
    private ExecutionQueueService queueService;
    private ReusableTriggerService triggerService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ExecutionQueueMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        if (queueService != null) {
            queueService.destroy();
        }
    }

    private void createService(int workerThreads, int timeoutSeconds) {
        triggerService = mock(ReusableTriggerService.class);
        queueService = new ExecutionQueueService(triggerService, metrics, workerThreads, timeoutSeconds);
    }

    // ----------------------------------------------------------------------
    // Direct publisher-layer checks (no queue interaction)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("Publishes aggregate (tenant=all) AND per-tenant series for enqueued events")
    void emitsAggregateAndPerTenantEnqueued() {
        metrics.recordEnqueued("PRO", "tenant-a", ExecutionQueueMetrics.PATH_FAST);
        metrics.recordEnqueued("PRO", "tenant-a", ExecutionQueueMetrics.PATH_SLOW);
        metrics.recordEnqueued("FREE", "tenant-b", ExecutionQueueMetrics.PATH_SLOW);

        assertEquals(3.0,
                sumCounters(ExecutionQueueMetrics.ENQUEUED_TOTAL, "tenant", ExecutionQueueMetrics.AGGREGATE),
                "aggregate must sum all enqueues regardless of path");
        assertEquals(2.0, sumCounters(ExecutionQueueMetrics.ENQUEUED_TOTAL, "tenant", "tenant-a"));
        assertEquals(1.0, sumCounters(ExecutionQueueMetrics.ENQUEUED_TOTAL, "tenant", "tenant-b"));
    }

    @Test
    @DisplayName("Enqueued counters carry full tag set (plan, tenant, path) - no schema drift")
    void enqueuedCountersCarryFullTagSet() {
        metrics.recordEnqueued("ENTERPRISE", "tenant-x", ExecutionQueueMetrics.PATH_FAST);

        Counter c = counterFor(ExecutionQueueMetrics.ENQUEUED_TOTAL, "tenant-x");
        // ENTERPRISE shorthand must normalize to ENTERPRISE_BASIC, matching PlanPriorityMapper semantics
        assertEquals("ENTERPRISE_BASIC", c.getId().getTag("plan"));
        assertEquals("tenant-x", c.getId().getTag("tenant"));
        assertEquals(ExecutionQueueMetrics.PATH_FAST, c.getId().getTag("path"));
    }

    @Test
    @DisplayName("Null/blank tenantId emits aggregate only - no stray non-aggregate series")
    void nullTenantEmitsAggregateOnly() {
        metrics.recordEnqueued("FREE", null, ExecutionQueueMetrics.PATH_SLOW);
        metrics.recordEnqueued("FREE", "  ", ExecutionQueueMetrics.PATH_FAST);

        assertEquals(2.0,
                sumCounters(ExecutionQueueMetrics.ENQUEUED_TOTAL, "tenant", ExecutionQueueMetrics.AGGREGATE));
        long nonAggregate = counters(ExecutionQueueMetrics.ENQUEUED_TOTAL).stream()
                .filter(c -> !ExecutionQueueMetrics.AGGREGATE.equals(c.getId().getTag("tenant")))
                .count();
        assertEquals(0, nonAggregate, "no per-tenant series should exist for null/blank tenantId");
    }

    @Test
    @DisplayName("Reserved tenantId=\"all\" is routed to aggregate only (no self-collision)")
    void literalAllTenantDoesNotCollideWithAggregate() {
        metrics.recordEnqueued("FREE", "all", ExecutionQueueMetrics.PATH_FAST);

        // Only one series - the aggregate - because the "all" literal is reserved
        assertEquals(1.0,
                sumCounters(ExecutionQueueMetrics.ENQUEUED_TOTAL, "tenant", ExecutionQueueMetrics.AGGREGATE));
        assertEquals(1, counters(ExecutionQueueMetrics.ENQUEUED_TOTAL).size(),
                "a literal tenantId=\"all\" must not spawn a second series");
    }

    @Test
    @DisplayName("Completed counter carries outcome tag and splits per tenant")
    void completedCountersSplitPerTenantAndOutcome() {
        metrics.recordCompleted("PRO", "tenant-a", ExecutionQueueMetrics.OUTCOME_SUCCESS);
        metrics.recordCompleted("PRO", "tenant-a", ExecutionQueueMetrics.OUTCOME_FAILURE);
        metrics.recordCompleted("FREE", "tenant-b", ExecutionQueueMetrics.OUTCOME_TIMEOUT);

        assertEquals(1.0, counterValue(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", "tenant-a", "outcome", ExecutionQueueMetrics.OUTCOME_SUCCESS));
        assertEquals(1.0, counterValue(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", "tenant-a", "outcome", ExecutionQueueMetrics.OUTCOME_FAILURE));
        assertEquals(1.0, counterValue(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", "tenant-b", "outcome", ExecutionQueueMetrics.OUTCOME_TIMEOUT));
        // Aggregate covers all three
        assertEquals(3.0,
                sumCounters(ExecutionQueueMetrics.COMPLETED_TOTAL, "tenant", ExecutionQueueMetrics.AGGREGATE));
    }

    @Test
    @DisplayName("Wait_ms counter only accumulates positive values; zero/negative is a no-op")
    void waitMsIgnoresNonPositive() {
        metrics.recordWaitMs("PRO", "tenant-a", 150);
        metrics.recordWaitMs("PRO", "tenant-a", 0);       // ignored
        metrics.recordWaitMs("PRO", "tenant-a", -50);     // ignored
        metrics.recordWaitMs("PRO", "tenant-a", 350);

        assertEquals(500.0, counterValue(ExecutionQueueMetrics.WAIT_MS_TOTAL,
                "tenant", "tenant-a", "plan", "PRO"));
        assertEquals(500.0, counterValue(ExecutionQueueMetrics.WAIT_MS_TOTAL,
                "tenant", ExecutionQueueMetrics.AGGREGATE, "plan", "PRO"));
    }

    @Test
    @DisplayName("active_tenants gauge tracks distinct tenants that enqueued at least once")
    void activeTenantsGaugeTracksDistinctTenants() {
        metrics.recordEnqueued("PRO", "tenant-a", ExecutionQueueMetrics.PATH_FAST);
        metrics.recordEnqueued("PRO", "tenant-a", ExecutionQueueMetrics.PATH_SLOW); // duplicate
        metrics.recordEnqueued("FREE", "tenant-b", ExecutionQueueMetrics.PATH_FAST);
        metrics.recordEnqueued("FREE", null, ExecutionQueueMetrics.PATH_SLOW); // does not count

        Gauge gauge = registry.find(ExecutionQueueMetrics.ACTIVE_TENANTS).gauge();
        assertNotNull(gauge);
        assertEquals(2.0, gauge.value(),
                "only distinct named tenants (tenant-a, tenant-b) count; null tenants are skipped");
    }

    // ----------------------------------------------------------------------
    // End-to-end through ExecutionQueueService
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("Fast-path execution records enqueue + completed, zero wait, and no queue depth")
    void fastPathRecordsEnqueueAndCompletion() {
        createService(5, 30);

        WorkflowRunEntity run = mockRun("run-1", "tenant-a");
        when(triggerService.executeTriggerInternal(eq(run), anyString(), any(), any(), anyBoolean()))
                .thenReturn(TriggerExecutionResult.success(
                        "run-1", "trigger:t", TriggerType.MANUAL, Set.of(), 1));

        TriggerExecutionResult result = queueService.enqueueAndWait(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "PRO");
        assertTrue(result.success());

        assertEquals(1.0, counterValue(ExecutionQueueMetrics.ENQUEUED_TOTAL,
                "tenant", "tenant-a", "path", ExecutionQueueMetrics.PATH_FAST));
        assertEquals(1.0, counterValue(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", "tenant-a", "outcome", ExecutionQueueMetrics.OUTCOME_SUCCESS));
        // No wait counter for fast path
        assertEquals(0.0,
                sumCounters(ExecutionQueueMetrics.WAIT_MS_TOTAL, "tenant", ExecutionQueueMetrics.AGGREGATE),
                "fast path must not accumulate wait time");
    }

    @Test
    @DisplayName("Slow-path execution accumulates wait_ms proportional to queue dwell, attributed per-tenant")
    void slowPathRecordsWaitMsPerTenant() throws Exception {
        // With 1 worker, the dispatcher holds the only permit waiting on queue.take(),
        // so both executions go through the slow path. The earlier one dwells briefly
        // (dispatcher picks it up immediately), the later one dwells ≥ the sleep gap.
        createService(1, 10);

        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerCanFinish = new CountDownLatch(1);

        WorkflowRunEntity blocker = mockRun("blocker", "tenant-blocker");
        WorkflowRunEntity queued = mockRun("queued", "tenant-queued");

        when(triggerService.executeTriggerInternal(eq(blocker), anyString(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    blockerStarted.countDown();
                    blockerCanFinish.await(5, TimeUnit.SECONDS);
                    return TriggerExecutionResult.success("blocker", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        when(triggerService.executeTriggerInternal(eq(queued), anyString(), any(), any(), anyBoolean()))
                .thenReturn(TriggerExecutionResult.success("queued", "trigger:t", TriggerType.MANUAL, Set.of(), 1));

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<TriggerExecutionResult> f1 = exec.submit(() -> queueService.enqueueAndWait(
                blocker, "trigger:t", TriggerType.MANUAL, Map.of(), "ENTERPRISE"));
        blockerStarted.await(5, TimeUnit.SECONDS);

        Future<TriggerExecutionResult> f2 = exec.submit(() -> queueService.enqueueAndWait(
                queued, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE"));

        Thread.sleep(250); // ensure queued item has dwelled measurably
        blockerCanFinish.countDown();

        assertTrue(f1.get(5, TimeUnit.SECONDS).success());
        assertTrue(f2.get(5, TimeUnit.SECONDS).success());

        double waitForQueued = counterValue(ExecutionQueueMetrics.WAIT_MS_TOTAL,
                "tenant", "tenant-queued", "plan", "FREE");
        assertTrue(waitForQueued >= 200,
                "queued tenant must accumulate ≥200ms of wait (expected ~250ms), got " + waitForQueued);

        // The blocker entered an empty queue and was picked up immediately by the dispatcher -
        // its wait is real but small, and MUST be strictly less than the queued-tenant's wait.
        double waitForBlocker = counterValue(ExecutionQueueMetrics.WAIT_MS_TOTAL,
                "tenant", "tenant-blocker", "plan", "ENTERPRISE_BASIC");
        assertTrue(waitForBlocker < waitForQueued,
                "tenant waiting in queue must accumulate more wait than tenant dispatched immediately - "
                        + "blocker=" + waitForBlocker + " queued=" + waitForQueued);

        // Aggregate series exists and equals the sum of per-tenant contributions (within ms jitter)
        double aggregate = sumCounters(ExecutionQueueMetrics.WAIT_MS_TOTAL,
                "tenant", ExecutionQueueMetrics.AGGREGATE);
        assertEquals(waitForQueued + waitForBlocker, aggregate, 1.0,
                "aggregate wait_ms must match the sum of per-tenant series");

        exec.shutdownNow();
    }

    @Test
    @DisplayName("Timeout bumps completed{outcome=timeout} and emits message with plan + runId failure")
    void timeoutBumpsTimeoutCounter() throws Exception {
        createService(1, 1); // 1s timeout

        CountDownLatch blocker = new CountDownLatch(1);
        WorkflowRunEntity blockerRun = mockRun("blocker", "tenant-blocker");
        WorkflowRunEntity timeoutRun = mockRun("to-run", "tenant-to");

        when(triggerService.executeTriggerInternal(eq(blockerRun), anyString(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    blocker.await(30, TimeUnit.SECONDS);
                    return TriggerExecutionResult.success("blocker", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });

        ExecutorService exec = Executors.newFixedThreadPool(2);
        exec.submit(() -> queueService.enqueueAndWait(
                blockerRun, "trigger:t", TriggerType.MANUAL, Map.of(), "ENTERPRISE"));
        Thread.sleep(200);

        TriggerExecutionResult r = queueService.enqueueAndWait(
                timeoutRun, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");
        assertFalse(r.success());

        // The timing-out caller is the assertion target: its per-tenant series must carry
        // exactly one OUTCOME_TIMEOUT. The blocker's caller may also time out (its
        // future.get(1s) bails while the worker thread is still running the mock), so we
        // assert on the tenant-to series only and require the aggregate to include it.
        assertEquals(1.0, counterValue(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", "tenant-to", "outcome", ExecutionQueueMetrics.OUTCOME_TIMEOUT));
        assertTrue(sumCounters(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", ExecutionQueueMetrics.AGGREGATE) >= 1.0,
                "aggregate completed must include the timeout outcome");

        blocker.countDown();
        exec.shutdownNow();
    }

    @Test
    @DisplayName("workers_busy gauge tracks in-flight executions (fast + slow) and drops to 0 on drain")
    void workersBusyGaugeTracksInFlight() throws Exception {
        createService(2, 10);

        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        WorkflowRunEntity run1 = mockRun("r1", "tenant-a");
        WorkflowRunEntity run2 = mockRun("r2", "tenant-b");

        when(triggerService.executeTriggerInternal(any(), anyString(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    bothStarted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    WorkflowRunEntity r = inv.getArgument(0);
                    return TriggerExecutionResult.success(r.getRunIdPublic(), "trigger:t",
                            TriggerType.MANUAL, Set.of(), 1);
                });

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<TriggerExecutionResult> f1 = exec.submit(() -> queueService.enqueueAndWait(
                run1, "trigger:t", TriggerType.MANUAL, Map.of(), "PRO"));
        Future<TriggerExecutionResult> f2 = exec.submit(() -> queueService.enqueueAndWait(
                run2, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE"));

        bothStarted.await(5, TimeUnit.SECONDS);

        Gauge busy = registry.find(ExecutionQueueMetrics.WORKERS_BUSY).gauge();
        assertNotNull(busy);
        assertEquals(2.0, busy.value(), "both workers must register as busy while executing");

        release.countDown();
        assertTrue(f1.get(5, TimeUnit.SECONDS).success());
        assertTrue(f2.get(5, TimeUnit.SECONDS).success());

        // Active executions drains back to 0 once futures complete
        Thread.sleep(100);
        assertEquals(0.0, busy.value(), "workers_busy must drop to zero once executions drain");

        exec.shutdownNow();
    }

    @Test
    @DisplayName("Fast-path execution that throws records completed{outcome=failure} and propagates")
    void fastPathExceptionRecordsFailureAndPropagates() {
        createService(5, 30);
        WorkflowRunEntity run = mockRun("boom", "tenant-a");
        when(triggerService.executeTriggerInternal(eq(run), anyString(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));

        assertThrows(RuntimeException.class, () -> queueService.enqueueAndWait(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "PRO"));

        // Enqueued + completed{failure} must both increment, so rate(enqueued) == rate(completed)
        // holds even on throw - no silent drops.
        assertEquals(1.0, counterValue(ExecutionQueueMetrics.ENQUEUED_TOTAL,
                "tenant", "tenant-a", "path", ExecutionQueueMetrics.PATH_FAST));
        assertEquals(1.0, counterValue(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", "tenant-a", "outcome", ExecutionQueueMetrics.OUTCOME_FAILURE));
    }

    @Test
    @DisplayName("Slow-path worker-thread exception records completed{outcome=failure} to the caller")
    void slowPathWorkerExceptionRecordsFailure() throws Exception {
        createService(1, 10);

        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerCanFinish = new CountDownLatch(1);
        WorkflowRunEntity blocker = mockRun("blocker", "tenant-blocker");
        WorkflowRunEntity failing = mockRun("failing", "tenant-failing");

        when(triggerService.executeTriggerInternal(eq(blocker), anyString(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    blockerStarted.countDown();
                    blockerCanFinish.await(5, TimeUnit.SECONDS);
                    return TriggerExecutionResult.success("blocker", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        when(triggerService.executeTriggerInternal(eq(failing), anyString(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("worker exploded"));

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<TriggerExecutionResult> f1 = exec.submit(() -> queueService.enqueueAndWait(
                blocker, "trigger:t", TriggerType.MANUAL, Map.of(), "ENTERPRISE"));
        blockerStarted.await(5, TimeUnit.SECONDS);

        Future<TriggerExecutionResult> f2 = exec.submit(() -> queueService.enqueueAndWait(
                failing, "trigger:t", TriggerType.MANUAL, Map.of(), "PRO"));

        Thread.sleep(100);
        blockerCanFinish.countDown();

        assertTrue(f1.get(5, TimeUnit.SECONDS).success());
        TriggerExecutionResult failingResult = f2.get(5, TimeUnit.SECONDS);
        assertFalse(failingResult.success(), "thrown worker exception must surface as failure");

        // The ExecutionException branch in enqueueAndWait must record OUTCOME_FAILURE
        assertEquals(1.0, counterValue(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", "tenant-failing", "outcome", ExecutionQueueMetrics.OUTCOME_FAILURE));
        exec.shutdownNow();
    }

    @Test
    @DisplayName("Unknown plan codes collapse to 'UNKNOWN' - plan tag cardinality stays bounded")
    void unknownPlanCollapsesToUnknown() {
        // Arbitrary marketing names must NOT become new series keys.
        metrics.recordEnqueued("GROWTH_HACK", "tenant-a", ExecutionQueueMetrics.PATH_FAST);
        metrics.recordEnqueued("launch_promo_2026", "tenant-b", ExecutionQueueMetrics.PATH_SLOW);

        // 2 calls × 2 counters each (aggregate + per-tenant) = 4 increments, all tagged plan="UNKNOWN".
        assertEquals(4.0,
                sumCounters(ExecutionQueueMetrics.ENQUEUED_TOTAL, "plan", "UNKNOWN"),
                "every unrecognized plan string must route to the single UNKNOWN plan tag");

        // Cardinality check: only one distinct plan tag value ("UNKNOWN") exists for the enqueued series.
        long distinctPlanTagsBeforeKnown = counters(ExecutionQueueMetrics.ENQUEUED_TOTAL).stream()
                .map(c -> c.getId().getTag("plan"))
                .distinct()
                .count();
        assertEquals(1L, distinctPlanTagsBeforeKnown,
                "unrecognized plan strings must not each create their own plan-tag series");

        // Known plans still resolve normally and add exactly one new distinct plan tag ("PRO").
        metrics.recordEnqueued("PRO", "tenant-c", ExecutionQueueMetrics.PATH_FAST);
        assertEquals(2.0,
                sumCounters(ExecutionQueueMetrics.ENQUEUED_TOTAL, "plan", "PRO"),
                "one PRO enqueue → aggregate counter + per-tenant counter = 2 increments");
    }

    @Test
    @DisplayName("Inactive tenants are pruned after TTL - active_tenants gauge reflects recent traffic only")
    void pruneDropsInactiveTenantsFromGauge() throws Exception {
        metrics.recordEnqueued("PRO", "tenant-active", ExecutionQueueMetrics.PATH_FAST);
        metrics.recordEnqueued("PRO", "tenant-stale", ExecutionQueueMetrics.PATH_FAST);

        Gauge gauge = registry.find(ExecutionQueueMetrics.ACTIVE_TENANTS).gauge();
        assertNotNull(gauge);
        assertEquals(2.0, gauge.value());

        // Age tenant-stale past the TTL via reflection so we don't wait 15 minutes
        java.lang.reflect.Field f = ExecutionQueueMetrics.class.getDeclaredField("recentlyActiveTenants");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> map = (Map<String, Long>) f.get(metrics);
        map.put("tenant-stale", 0L);

        metrics.pruneInactiveTenants();

        assertEquals(1.0, gauge.value(),
                "stale tenant must be pruned; active tenant must remain");
        assertFalse(map.containsKey("tenant-stale"));
        assertTrue(map.containsKey("tenant-active"));
    }

    @Test
    @DisplayName("Cancelled-before-dispatch items do NOT emit wait_ms (no inflated latency for ghost work)")
    void cancelledItemDoesNotRecordWaitMs() throws Exception {
        createService(1, 10);

        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerCanFinish = new CountDownLatch(1);
        WorkflowRunEntity blocker = mockRun("blocker", "tenant-blocker");
        WorkflowRunEntity cancelled = mockRun("cancelled", "tenant-cancelled");

        when(triggerService.executeTriggerInternal(eq(blocker), anyString(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    blockerStarted.countDown();
                    blockerCanFinish.await(5, TimeUnit.SECONDS);
                    return TriggerExecutionResult.success("blocker", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<TriggerExecutionResult> f1 = exec.submit(() -> queueService.enqueueAndWait(
                blocker, "trigger:t", TriggerType.MANUAL, Map.of(), "ENTERPRISE"));
        blockerStarted.await(5, TimeUnit.SECONDS);

        // Submit then immediately interrupt the queued caller so its item is cancelled
        // before the worker picks it up (when blocker releases).
        Thread interruptible = new Thread(() -> queueService.enqueueAndWait(
                cancelled, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE"));
        interruptible.start();
        Thread.sleep(100);
        interruptible.interrupt();
        interruptible.join(5_000);

        // Release blocker; dispatcher now picks up the (cancelled) item and must skip it.
        blockerCanFinish.countDown();
        assertTrue(f1.get(5, TimeUnit.SECONDS).success());
        Thread.sleep(100); // let dispatcher see the cancelled item

        // Interrupted caller recorded OUTCOME_CANCELLED; dispatcher side emitted NO wait_ms
        assertEquals(1.0, counterValue(ExecutionQueueMetrics.COMPLETED_TOTAL,
                "tenant", "tenant-cancelled", "outcome", ExecutionQueueMetrics.OUTCOME_CANCELLED));
        long cancelledWaitSeries = counters(ExecutionQueueMetrics.WAIT_MS_TOTAL).stream()
                .filter(c -> "tenant-cancelled".equals(c.getId().getTag("tenant")))
                .count();
        assertEquals(0, cancelledWaitSeries,
                "cancelled-before-execute items must not inflate wait_ms (no ghost latency)");

        exec.shutdownNow();
    }

    @Test
    @DisplayName("All workflow_queue_* counters share the same tag schema (no Micrometer conflict)")
    void allQueueCountersShareTagSchema() {
        // drive each counter name through both public recorders
        metrics.recordEnqueued("PRO", "tenant-a", ExecutionQueueMetrics.PATH_FAST);
        metrics.recordEnqueued("PRO", "tenant-a", ExecutionQueueMetrics.PATH_SLOW);
        metrics.recordCompleted("PRO", "tenant-a", ExecutionQueueMetrics.OUTCOME_SUCCESS);
        metrics.recordCompleted("PRO", "tenant-a", ExecutionQueueMetrics.OUTCOME_TIMEOUT);
        metrics.recordWaitMs("PRO", "tenant-a", 123);

        List<String> names = List.of(
                ExecutionQueueMetrics.ENQUEUED_TOTAL,
                ExecutionQueueMetrics.COMPLETED_TOTAL,
                ExecutionQueueMetrics.WAIT_MS_TOTAL);
        int inspected = 0;
        for (String name : names) {
            List<Counter> list = counters(name);
            assertFalse(list.isEmpty(), name + " must have at least one registered series");
            for (Counter c : list) {
                assertNotNull(c.getId().getTag("plan"), name + " missing 'plan' tag");
                assertNotNull(c.getId().getTag("tenant"), name + " missing 'tenant' tag");
                inspected++;
            }
        }
        assertTrue(inspected >= 6,
                "expected >= 6 counter series across enqueued/completed/wait_ms, saw " + inspected);
    }

    @Test
    @DisplayName("Queue gauges survive GC - suppliers held by strong field refs, not just weak Micrometer refs")
    void queueGaugesSurviveGC() throws Exception {
        // Regression: before the fix, ExecutionQueueMetrics registered gauges with
        // lambdas only referenced locally inside bindQueueGauges(); Micrometer
        // wraps the 2nd arg in a WeakReference, so once the caller's frame
        // returned the lambdas were GC-eligible and the gauge reported NaN.
        //
        // The fix stores the IntSupplier in an instance field before registering.
        // This test fires a narrow-scope lambda, nulls its only external handle,
        // forces a GC, then asserts the gauge still returns the live value.

        int[] holder = new int[] { 7 };
        // Scope the suppliers so no test-method-local handle keeps them alive.
        ((Runnable) () -> metrics.bindQueueGauges(
                () -> holder[0],
                () -> holder[0] * 2,
                () -> holder[0] * 3)).run();

        System.gc();
        Thread.sleep(50);
        System.gc();

        Gauge depth = registry.find(ExecutionQueueMetrics.DEPTH).gauge();
        Gauge available = registry.find(ExecutionQueueMetrics.WORKERS_AVAILABLE).gauge();
        Gauge busy = registry.find(ExecutionQueueMetrics.WORKERS_BUSY).gauge();
        assertNotNull(depth);
        assertNotNull(available);
        assertNotNull(busy);
        assertEquals(7.0, depth.value(),
                "depth gauge must return live value (not NaN) after GC");
        assertEquals(14.0, available.value(),
                "workers_available gauge must return live value (not NaN) after GC");
        assertEquals(21.0, busy.value(),
                "workers_busy gauge must return live value (not NaN) after GC");
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private List<Counter> counters(String name) {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(name))
                .filter(m -> m instanceof Counter)
                .map(m -> (Counter) m)
                .collect(Collectors.toList());
    }

    private Counter counterFor(String name, String tenant) {
        return counters(name).stream()
                .filter(c -> tenant.equals(c.getId().getTag("tenant")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        name + "{tenant=" + tenant + "} not registered. Present: "
                                + counters(name).stream().map(Meter::getId).toList()));
    }

    /**
     * Sum counters filtered by a single tag key=value.
     */
    private double sumCounters(String name, String tagKey, String tagValue) {
        return counters(name).stream()
                .filter(c -> tagValue.equals(c.getId().getTag(tagKey)))
                .mapToDouble(Counter::count)
                .sum();
    }

    /**
     * Return the value of a single counter series matching all provided (key, value) tag pairs,
     * or 0.0 if no matching series exists.
     */
    private double counterValue(String name, String... kvPairs) {
        return counters(name).stream()
                .filter(c -> {
                    for (int i = 0; i < kvPairs.length; i += 2) {
                        if (!kvPairs[i + 1].equals(c.getId().getTag(kvPairs[i]))) return false;
                    }
                    return true;
                })
                .mapToDouble(Counter::count)
                .sum();
    }

    private static WorkflowRunEntity mockRun(String runId, String tenantId) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getRunIdPublic()).thenReturn(runId);
        when(run.getTenantId()).thenReturn(tenantId);
        return run;
    }
}
