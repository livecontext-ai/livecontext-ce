package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionQueueServiceTest {

    private ExecutionQueueService queueService;
    private ReusableTriggerService triggerService;

    @AfterEach
    void tearDown() throws Exception {
        if (queueService != null) {
            queueService.destroy();
        }
    }

    private void createService(int workerThreads, int timeoutSeconds) {
        triggerService = mock(ReusableTriggerService.class);
        ExecutionQueueMetrics metrics = new ExecutionQueueMetrics(new SimpleMeterRegistry());
        queueService = new ExecutionQueueService(triggerService, metrics, workerThreads, timeoutSeconds);
    }

    @Test
    void fastPathExecutesImmediatelyWhenWorkersAvailable() {
        createService(5, 300);

        WorkflowRunEntity run = mockRun("run-1");
        TriggerExecutionResult expected = TriggerExecutionResult.success(
            "run-1", "trigger:test", TriggerType.MANUAL, Set.of(), 1);

        when(triggerService.executeTriggerInternal(run, "trigger:test", TriggerType.MANUAL, Map.of(), false))
            .thenReturn(expected);

        TriggerExecutionResult result = queueService.enqueueAndWait(
            run, "trigger:test", TriggerType.MANUAL, Map.of(), "PRO");

        assertTrue(result.success());
        assertEquals("run-1", result.runId());
        verify(triggerService).executeTriggerInternal(run, "trigger:test", TriggerType.MANUAL, Map.of(), false);
    }

    @Test
    void slowPathQueuesAndExecutesWhenWorkerBecomesAvailable() throws Exception {
        // 1 worker thread, so second call must wait in queue
        createService(1, 10);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstCanFinish = new CountDownLatch(1);

        WorkflowRunEntity run1 = mockRun("run-1");
        WorkflowRunEntity run2 = mockRun("run-2");

        when(triggerService.executeTriggerInternal(eq(run1), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                firstStarted.countDown();
                firstCanFinish.await(5, TimeUnit.SECONDS);
                return TriggerExecutionResult.success("run-1", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
            });

        when(triggerService.executeTriggerInternal(eq(run2), anyString(), any(), any(), anyBoolean()))
            .thenReturn(TriggerExecutionResult.success("run-2", "trigger:t", TriggerType.MANUAL, Set.of(), 1));

        // Start first execution (takes the only worker)
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<TriggerExecutionResult> f1 = executor.submit(() ->
            queueService.enqueueAndWait(run1, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE"));

        firstStarted.await(5, TimeUnit.SECONDS);

        // Second execution should queue (no worker available)
        Future<TriggerExecutionResult> f2 = executor.submit(() ->
            queueService.enqueueAndWait(run2, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE"));

        // Give it a moment to confirm it's queued
        Thread.sleep(200);
        assertFalse(f2.isDone(), "Second execution should be queued");

        // Release first worker
        firstCanFinish.countDown();

        // Both should complete
        TriggerExecutionResult r1 = f1.get(5, TimeUnit.SECONDS);
        TriggerExecutionResult r2 = f2.get(5, TimeUnit.SECONDS);

        assertTrue(r1.success());
        assertTrue(r2.success());

        executor.shutdownNow();
    }

    @Test
    void timeoutReturnsFailureWithUpgradeMessage() {
        // 0 effective workers: set 1 worker but block it permanently
        createService(1, 2); // 2-second timeout

        CountDownLatch blocker = new CountDownLatch(1);
        WorkflowRunEntity blockingRun = mockRun("blocker");
        WorkflowRunEntity timeoutRun = mockRun("timeout-run");

        when(triggerService.executeTriggerInternal(eq(blockingRun), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                blocker.await(30, TimeUnit.SECONDS); // hold the worker
                return TriggerExecutionResult.success("blocker", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
            });

        when(triggerService.executeTriggerInternal(eq(timeoutRun), anyString(), any(), any(), anyBoolean()))
            .thenReturn(TriggerExecutionResult.success("timeout-run", "trigger:t", TriggerType.MANUAL, Set.of(), 1));

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Block the only worker
        executor.submit(() ->
            queueService.enqueueAndWait(blockingRun, "trigger:t", TriggerType.MANUAL, Map.of(), "ENTERPRISE"));

        // Give time for blocker to acquire the worker
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        // This should timeout
        TriggerExecutionResult result = queueService.enqueueAndWait(
            timeoutRun, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");

        assertFalse(result.success());
        assertTrue(result.message().contains("queue timeout"), "Message should mention timeout");
        assertTrue(result.message().contains("FREE"), "Message should mention current plan");
        assertTrue(result.message().contains("Upgrade"), "Message should suggest upgrade");

        blocker.countDown();
        executor.shutdownNow();
    }

    @Test
    void enterpriseExecutesBeforeFreeWhenBothQueued() throws Exception {
        createService(1, 10);

        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        AtomicReference<String> firstDequeued = new AtomicReference<>();

        WorkflowRunEntity blockingRun = mockRun("blocker");
        WorkflowRunEntity freeRun = mockRun("free-run");
        WorkflowRunEntity enterpriseRun = mockRun("enterprise-run");

        // Blocker holds the single worker
        when(triggerService.executeTriggerInternal(eq(blockingRun), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                blockerStarted.countDown();
                blocker.await(10, TimeUnit.SECONDS);
                return TriggerExecutionResult.success("blocker", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
            });

        AtomicInteger executionOrder = new AtomicInteger(0);
        when(triggerService.executeTriggerInternal(eq(freeRun), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                firstDequeued.compareAndSet(null, "FREE");
                return TriggerExecutionResult.success("free-run", "trigger:t", TriggerType.MANUAL,
                    "order:" + executionOrder.incrementAndGet(), Set.of(), 1);
            });

        when(triggerService.executeTriggerInternal(eq(enterpriseRun), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                firstDequeued.compareAndSet(null, "ENTERPRISE");
                return TriggerExecutionResult.success("enterprise-run", "trigger:t", TriggerType.MANUAL,
                    "order:" + executionOrder.incrementAndGet(), Set.of(), 1);
            });

        ExecutorService executor = Executors.newFixedThreadPool(4);

        // Block the worker
        executor.submit(() ->
            queueService.enqueueAndWait(blockingRun, "trigger:t", TriggerType.MANUAL, Map.of(), "ENTERPRISE"));
        blockerStarted.await(5, TimeUnit.SECONDS);

        // Queue FREE first, then ENTERPRISE
        Future<TriggerExecutionResult> freeFuture = executor.submit(() ->
            queueService.enqueueAndWait(freeRun, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE"));

        Thread.sleep(100); // ensure FREE is queued first

        Future<TriggerExecutionResult> enterpriseFuture = executor.submit(() ->
            queueService.enqueueAndWait(enterpriseRun, "trigger:t", TriggerType.MANUAL, Map.of(), "ENTERPRISE"));

        Thread.sleep(200); // let both settle into queue

        // Release blocker - enterprise should be dequeued first
        blocker.countDown();

        TriggerExecutionResult freeResult = freeFuture.get(10, TimeUnit.SECONDS);
        TriggerExecutionResult enterpriseResult = enterpriseFuture.get(10, TimeUnit.SECONDS);

        assertTrue(freeResult.success());
        assertTrue(enterpriseResult.success());

        // Enterprise should have been the first dequeued after blocker released
        assertEquals("ENTERPRISE", firstDequeued.get(),
            "Enterprise should be dequeued before Free");

        executor.shutdownNow();
    }

    @Test
    void shutdownDrainsPendingItems() throws Exception {
        createService(1, 300);

        CountDownLatch blocker = new CountDownLatch(1);
        WorkflowRunEntity blockingRun = mockRun("blocker");
        WorkflowRunEntity pendingRun = mockRun("pending");

        when(triggerService.executeTriggerInternal(eq(blockingRun), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                blocker.await(30, TimeUnit.SECONDS);
                return TriggerExecutionResult.success("blocker", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
            });

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Block the worker
        executor.submit(() ->
            queueService.enqueueAndWait(blockingRun, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE"));
        Thread.sleep(200);

        // Queue another item
        Future<TriggerExecutionResult> pendingFuture = executor.submit(() ->
            queueService.enqueueAndWait(pendingRun, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE"));
        Thread.sleep(200);

        // Shutdown should drain and complete pending with failure
        blocker.countDown();
        queueService.destroy();
        queueService = null; // prevent double-destroy in tearDown

        executor.shutdownNow();
    }

    // ========================================================================
    // enqueueAsync (fire-and-forget) - added when HTTP triggers in AUTOMATIC
    // mode were switched to non-blocking dispatch so the Tomcat thread is
    // freed instead of waiting ~10s for a Gmail-sized epoch.
    // ========================================================================

    @Test
    void enqueueAsyncReturnsImmediatelyWithAcceptedResult() throws Exception {
        createService(5, 300);
        WorkflowRunEntity run = mockRun("run-1");
        CountDownLatch executeStarted = new CountDownLatch(1);
        CountDownLatch holdExecution = new CountDownLatch(1);

        when(triggerService.executeTriggerInternal(eq(run), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                executeStarted.countDown();
                holdExecution.await(5, TimeUnit.SECONDS);
                return TriggerExecutionResult.success("run-1", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
            });

        long before = System.nanoTime();
        TriggerExecutionResult ack = queueService.enqueueAsync(
            run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        // Caller must NOT have waited for the workflow to finish.
        assertTrue(ack.success(), "ack should report success=true");
        assertEquals(-1, ack.epoch(), "epoch should be -1 (unknown until SSE)");
        assertTrue(ack.readySteps().isEmpty(), "readySteps should be empty for accepted result");
        assertTrue(elapsedMs < 200, "enqueueAsync should return immediately, took " + elapsedMs + "ms");

        // Worker should have started executing on a separate thread.
        assertTrue(executeStarted.await(2, TimeUnit.SECONDS),
            "worker should have begun executeTriggerInternal asynchronously");

        holdExecution.countDown();
    }

    @Test
    void enqueueAsyncSlowPathQueuesWhenAllWorkersBusy() throws Exception {
        createService(1, 10);
        WorkflowRunEntity blockingRun = mockRun("blocking");
        WorkflowRunEntity queuedRun = mockRun("queued");

        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerHold = new CountDownLatch(1);
        CountDownLatch queuedExecuted = new CountDownLatch(1);

        when(triggerService.executeTriggerInternal(eq(blockingRun), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                blockerStarted.countDown();
                blockerHold.await(5, TimeUnit.SECONDS);
                return TriggerExecutionResult.success("blocking", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
            });
        when(triggerService.executeTriggerInternal(eq(queuedRun), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                queuedExecuted.countDown();
                return TriggerExecutionResult.success("queued", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
            });

        // Saturate the worker
        TriggerExecutionResult ack1 = queueService.enqueueAsync(
            blockingRun, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");
        assertEquals(-1, ack1.epoch());
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));

        // Second async fire - no worker available → must take the slow path (queue),
        // still return immediately with an accepted result.
        long before = System.nanoTime();
        TriggerExecutionResult ack2 = queueService.enqueueAsync(
            queuedRun, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;
        assertTrue(ack2.success());
        assertEquals(-1, ack2.epoch());
        assertTrue(elapsedMs < 200, "slow-path enqueueAsync should still return immediately, took " + elapsedMs + "ms");
        assertTrue(queueService.getQueueSize() >= 1
                || queueService.getAvailableWorkers() == 0,
            "queued item should be visible in queue or already dispatched");

        // Release the blocker - the queued item must eventually run via dispatcher.
        blockerHold.countDown();
        assertTrue(queuedExecuted.await(5, TimeUnit.SECONDS),
            "dispatcher should have picked up the orphan-future queued item");
    }

    @Test
    void enqueueAsyncDrainsLargeBacklogAfterWorkerSaturation() throws Exception {
        createService(1, 30);

        int queuedRuns = 75;
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch queuedExecuted = new CountDownLatch(queuedRuns);
        ConcurrentLinkedQueue<String> executedRunIds = new ConcurrentLinkedQueue<>();

        when(triggerService.executeTriggerInternal(any(WorkflowRunEntity.class), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                WorkflowRunEntity run = inv.getArgument(0);
                String runId = run.getRunIdPublic();
                String triggerId = inv.getArgument(1);
                TriggerType triggerType = inv.getArgument(2);

                if ("blocking-run".equals(runId)) {
                    blockerStarted.countDown();
                    releaseBlocker.await(10, TimeUnit.SECONDS);
                } else {
                    executedRunIds.add(runId);
                    queuedExecuted.countDown();
                }
                return TriggerExecutionResult.success(runId, triggerId, triggerType, Set.of(), 1);
            });

        TriggerExecutionResult blockerAck = queueService.enqueueAsync(
            mockRun("blocking-run"), "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");
        assertTrue(blockerAck.success(), "blocking run should be accepted");
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS),
            "blocking run should occupy the only worker before queuing the backlog");

        List<String> queuedRunIds = new ArrayList<>();
        for (int i = 0; i < queuedRuns; i++) {
            String runId = "queued-run-" + i;
            queuedRunIds.add(runId);
            TriggerExecutionResult ack = queueService.enqueueAsync(
                mockRun(runId), "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");

            assertTrue(ack.success(), "queued run should be accepted: " + runId);
            assertEquals(-1, ack.epoch(), "async acknowledgement should not pretend the run already executed");
        }

        assertEquals(queuedRuns, queueService.getQueueSize(),
            "all queued workflows should remain in the queue while the only worker is blocked");

        releaseBlocker.countDown();
        assertTrue(queuedExecuted.await(15, TimeUnit.SECONDS),
            "dispatcher should execute every queued workflow after the worker is released");
        waitUntil(() -> queueService.getQueueSize() == 0,
            "queue should drain completely after the backlog executes");

        assertEquals(queuedRuns, new HashSet<>(executedRunIds).size(),
            "each queued workflow should execute exactly once");
        assertTrue(executedRunIds.containsAll(queuedRunIds),
            "every queued workflow should be observed by the trigger service");
    }

    @Test
    void enqueueAsyncDrainsMixedTenantOrganizationBacklogWithWorkerScope() throws Exception {
        createService(2, 30);

        int totalRuns = 36;
        CountDownLatch allExecuted = new CountDownLatch(totalRuns);
        ConcurrentMap<String, String> expectedOrgByRunId = new ConcurrentHashMap<>();
        ConcurrentMap<String, String> observedOrgByRunId = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<String> executedRunIds = new ConcurrentLinkedQueue<>();

        when(triggerService.executeTriggerInternal(any(WorkflowRunEntity.class), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                WorkflowRunEntity run = inv.getArgument(0);
                String runId = run.getRunIdPublic();
                String triggerId = inv.getArgument(1);
                TriggerType triggerType = inv.getArgument(2);

                executedRunIds.add(runId);
                observedOrgByRunId.put(runId, TenantResolver.currentRequestOrganizationId());
                Thread.sleep(5);
                allExecuted.countDown();
                return TriggerExecutionResult.success(runId, triggerId, triggerType, Set.of(), 1);
            });

        for (int i = 0; i < totalRuns; i++) {
            String runId = "org-queue-run-" + i;
            String tenantId = i % 2 == 0 ? "tenant-alpha-" + i : "tenant-beta-" + i;
            String organizationId = i % 3 == 0 ? "org-shared" : "org-isolated-" + i;
            expectedOrgByRunId.put(runId, organizationId);

            TriggerExecutionResult ack = queueService.enqueueAsync(
                mockRun(runId, tenantId, organizationId),
                "trigger:t",
                TriggerType.MANUAL,
                Map.of("index", i),
                i % 4 == 0 ? "ENTERPRISE" : "FREE");

            assertTrue(ack.success(), "async execution should be accepted: " + runId);
            assertEquals(-1, ack.epoch(), "async acknowledgement should not claim completion");
        }

        assertTrue(allExecuted.await(10, TimeUnit.SECONDS),
            "queue should drain every mixed-organization async execution");
        waitUntil(() -> queueService.getQueueSize() == 0,
            "queue should be empty after mixed-organization backlog drains");

        assertEquals(totalRuns, new HashSet<>(executedRunIds).size(),
            "every queued run should execute exactly once");
        assertEquals(expectedOrgByRunId, observedOrgByRunId,
            "each queued run must execute with its own organization scope");
        verify(triggerService, times(totalRuns))
            .executeTriggerInternal(any(WorkflowRunEntity.class), eq("trigger:t"), eq(TriggerType.MANUAL), any(), eq(false));
    }

    @Test
    void enqueueAsyncDrainsOneHundredConcurrentLongWorkflowsWithUserScopeIsolation() throws Exception {
        int workerThreads = 4;
        createService(workerThreads, 60);

        int totalUsers = 100;
        CountDownLatch blockerStarted = new CountDownLatch(workerThreads);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        CountDownLatch allUsersExecuted = new CountDownLatch(totalUsers);
        ConcurrentMap<String, String> expectedOrgByRunId = new ConcurrentHashMap<>();
        ConcurrentMap<String, String> observedOrgByRunId = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<String> executedRunIds = new ConcurrentLinkedQueue<>();
        AtomicInteger activeUserExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrentUserExecutions = new AtomicInteger(0);

        when(triggerService.executeTriggerInternal(any(WorkflowRunEntity.class), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                WorkflowRunEntity run = inv.getArgument(0);
                String runId = run.getRunIdPublic();
                String triggerId = inv.getArgument(1);
                TriggerType triggerType = inv.getArgument(2);

                if (runId.startsWith("blocking-run-")) {
                    blockerStarted.countDown();
                    releaseBlockers.await(10, TimeUnit.SECONDS);
                } else {
                    int active = activeUserExecutions.incrementAndGet();
                    maxConcurrentUserExecutions.accumulateAndGet(active, Math::max);
                    try {
                        executedRunIds.add(runId);
                        observedOrgByRunId.put(runId, TenantResolver.currentRequestOrganizationId());
                        Thread.sleep(20);
                        allUsersExecuted.countDown();
                    } finally {
                        activeUserExecutions.decrementAndGet();
                    }
                }

                return TriggerExecutionResult.success(runId, triggerId, triggerType, Set.of(), 1);
            });

        for (int i = 0; i < workerThreads; i++) {
            TriggerExecutionResult ack = queueService.enqueueAsync(
                mockRun("blocking-run-" + i, "tenant-blocker-" + i, "org-blocker-" + i),
                "trigger:t",
                TriggerType.MANUAL,
                Map.of("blocker", i),
                "ENTERPRISE");
            assertTrue(ack.success(), "blocking workflow should be accepted: " + i);
        }

        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS),
            "all workers should be occupied before the 100-user burst is submitted");

        ExecutorService callers = Executors.newFixedThreadPool(totalUsers);
        CountDownLatch callersReady = new CountDownLatch(totalUsers);
        CountDownLatch releaseCallers = new CountDownLatch(1);
        List<Future<TriggerExecutionResult>> futures = new ArrayList<>();
        List<String> runIds = new ArrayList<>();

        for (int i = 0; i < totalUsers; i++) {
            String runId = "concurrent-user-run-" + i;
            String tenantId = "tenant-user-" + i;
            String organizationId = "org-burst-" + (i % 10);
            runIds.add(runId);
            expectedOrgByRunId.put(runId, organizationId);
            WorkflowRunEntity run = mockRun(runId, tenantId, organizationId);
            String userPlan = i % 5 == 0 ? "ENTERPRISE" : "FREE";

            futures.add(callers.submit(() -> {
                callersReady.countDown();
                assertTrue(releaseCallers.await(5, TimeUnit.SECONDS),
                    "all user callers should be released together");
                return queueService.enqueueAsync(
                    run,
                    "trigger:t",
                    TriggerType.MANUAL,
                    Map.of("userRunId", runId),
                    userPlan);
            }));
        }

        assertTrue(callersReady.await(5, TimeUnit.SECONDS),
            "all 100 callers should be ready before the burst starts");
        releaseCallers.countDown();

        for (Future<TriggerExecutionResult> future : futures) {
            TriggerExecutionResult ack = future.get(5, TimeUnit.SECONDS);
            assertTrue(ack.success(), "each concurrent user should receive an accepted async result");
            assertEquals(-1, ack.epoch(), "async acknowledgement should not claim completion");
        }
        callers.shutdownNow();

        assertEquals(totalUsers, queueService.getQueueSize(),
            "all user workflows should queue while every worker is occupied by long-running workflows");

        releaseBlockers.countDown();
        assertTrue(allUsersExecuted.await(20, TimeUnit.SECONDS),
            "queue should drain all 100 long user workflows after workers are released");
        waitUntil(() -> queueService.getQueueSize() == 0,
            "queue should be empty after the 100-user burst drains");

        assertEquals(totalUsers, new HashSet<>(executedRunIds).size(),
            "each user workflow should execute exactly once");
        assertTrue(executedRunIds.containsAll(runIds),
            "every submitted user workflow should reach the trigger service");
        assertEquals(expectedOrgByRunId, observedOrgByRunId,
            "each user workflow must execute with its own organization scope");
        assertTrue(maxConcurrentUserExecutions.get() <= workerThreads,
            "queue must not execute more user workflows concurrently than available workers");
        assertTrue(maxConcurrentUserExecutions.get() > 1,
            "queued long workflows should drain in parallel once workers are released");
    }

    @Test
    void enqueueAndWaitDrainsLargeBacklogOfWaitingCallers() throws Exception {
        createService(1, 30);

        int totalRuns = 50;
        CountDownLatch callersReady = new CountDownLatch(totalRuns);
        CountDownLatch releaseCallers = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> executedRunIds = new ConcurrentLinkedQueue<>();

        when(triggerService.executeTriggerInternal(any(WorkflowRunEntity.class), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                WorkflowRunEntity run = inv.getArgument(0);
                String runId = run.getRunIdPublic();
                String triggerId = inv.getArgument(1);
                TriggerType triggerType = inv.getArgument(2);

                executedRunIds.add(runId);
                Thread.sleep(5);
                return TriggerExecutionResult.success(runId, triggerId, triggerType, Set.of(), 1);
            });

        ExecutorService callers = Executors.newFixedThreadPool(totalRuns);
        List<Future<TriggerExecutionResult>> futures = new ArrayList<>();
        List<String> runIds = new ArrayList<>();
        for (int i = 0; i < totalRuns; i++) {
            String runId = "sync-run-" + i;
            runIds.add(runId);
            WorkflowRunEntity run = mockRun(runId);
            futures.add(callers.submit(() -> {
                callersReady.countDown();
                assertTrue(releaseCallers.await(5, TimeUnit.SECONDS),
                    "all caller tasks should be released together");
                return queueService.enqueueAndWait(run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");
            }));
        }

        assertTrue(callersReady.await(5, TimeUnit.SECONDS),
            "all caller tasks should be ready before starting the stress run");
        releaseCallers.countDown();

        for (Future<TriggerExecutionResult> future : futures) {
            TriggerExecutionResult result = future.get(20, TimeUnit.SECONDS);
            assertTrue(result.success(), "every queued caller should receive a successful result");
        }
        waitUntil(() -> queueService.getQueueSize() == 0,
            "queue should drain after every waiting caller completes");

        assertEquals(totalRuns, new HashSet<>(executedRunIds).size(),
            "each synchronous workflow should execute exactly once");
        assertTrue(executedRunIds.containsAll(runIds),
            "all submitted synchronous workflows should reach the trigger service");

        callers.shutdownNow();
    }

    @Test
    void enqueueAsyncReleasesPermitOnExecutionException() throws Exception {
        // Use 3 workers so the dispatcher holds 1 permit (blocked on queue.take())
        // and 2 are available for the test. This makes the "did the failing
        // fast-path runnable release its permit?" check unambiguous: if the
        // worker leaks, availableWorkers stays at 1; if it releases, it goes
        // back to 2.
        createService(3, 5);
        Thread.sleep(50); // let dispatcher acquire its idle permit
        int baseline = queueService.getAvailableWorkers();
        assertEquals(2, baseline, "expected 2 free permits (dispatcher holds 1)");

        WorkflowRunEntity run = mockRun("boom");
        CountDownLatch boomFinished = new CountDownLatch(1);
        when(triggerService.executeTriggerInternal(eq(run), anyString(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> {
                try {
                    throw new RuntimeException("simulated worker failure");
                } finally {
                    boomFinished.countDown();
                }
            });

        TriggerExecutionResult ack = queueService.enqueueAsync(
            run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");
        assertTrue(ack.success(), "ack must still report success - failure happens later in worker");

        assertTrue(boomFinished.await(2, TimeUnit.SECONDS), "worker should have run and thrown");

        // Wait for the finally-block release to be visible. Spin briefly.
        long deadline = System.currentTimeMillis() + 2000;
        while (queueService.getAvailableWorkers() != baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(baseline, queueService.getAvailableWorkers(),
            "permit must have been released by the failing fast-path worker (no shrinkage)");
    }

    @Test
    void enqueueAsyncRecoversWhenWorkerPoolRejectsSubmission() throws Exception {
        // Regression for the must-fix: if workerPool.submit throws
        // RejectedExecutionException (race between Spring shutdown and a
        // mid-flight HTTP request), the fast path must:
        //   (1) release the semaphore permit (otherwise pool capacity shrinks
        //       permanently across hot-restart),
        //   (2) decrement activeExecutions,
        //   (3) record OUTCOME_FAILURE (so rate(enqueued)==rate(completed)),
        //   (4) return a failure result instead of a stale "accepted" ack.
        createService(3, 5);
        Thread.sleep(50); // let dispatcher acquire its idle permit
        int baselineBeforeShutdown = queueService.getAvailableWorkers();

        // destroy() interrupts the dispatcher (does NOT release its permit -
        // permits aren't thread-bound) and calls workerPool.shutdown(). The
        // semaphore retains all the permits it had at that moment. So:
        //   - tryAcquire on a fresh enqueueAsync still succeeds,
        //   - workerPool.submit then throws RejectedExecutionException,
        //   - the catch branch must roll back the permit + record metric +
        //     return failure(...).
        queueService.destroy();

        TriggerExecutionResult result = queueService.enqueueAsync(
            mockRun("rejected"), "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");

        assertFalse(result.success(),
            "must return failure when workerPool rejects submission");
        assertNotNull(result.message());
        assertTrue(result.message().toLowerCase().contains("shutting down")
                || result.message().toLowerCase().contains("shutdown"),
            "failure message should mention shutdown, was: " + result.message());

        // Permit must have been rolled back to baseline - pool capacity does
        // not shrink across the rejected submission.
        assertEquals(baselineBeforeShutdown, queueService.getAvailableWorkers(),
            "permit must be rolled back after RejectedExecutionException - pool capacity must not shrink");

        queueService = null; // tearDown should not double-destroy
    }

    @Test
    void monitoringMethodsWork() throws Exception {
        createService(10, 300);
        // Dispatcher thread holds 1 permit while waiting on queue.take(), so
        // available = workerThreads - 1 when the queue is empty. Poll for the
        // dispatcher to actually reach queue.take() instead of a fixed 50ms sleep
        // (which raced on slow CI runners before the dispatcher had taken its permit).
        waitUntil(() -> queueService.getAvailableWorkers() == 9,
                "dispatcher should hold 1 permit while waiting on queue.take()");
        assertEquals(9, queueService.getAvailableWorkers());
        assertEquals(0, queueService.getQueueSize());
    }

    private WorkflowRunEntity mockRun(String runId) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getRunIdPublic()).thenReturn(runId);
        return run;
    }

    private WorkflowRunEntity mockRun(String runId, String tenantId, String organizationId) {
        WorkflowRunEntity run = mockRun(runId);
        when(run.getTenantId()).thenReturn(tenantId);
        when(run.getOrganizationId()).thenReturn(organizationId);
        return run;
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }
}
