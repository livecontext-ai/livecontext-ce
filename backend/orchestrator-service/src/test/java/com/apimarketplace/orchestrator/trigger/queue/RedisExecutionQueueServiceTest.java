package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.common.event.InMemoryKeyValueStore;
import com.apimarketplace.common.scaling.lock.InMemorySemaphore;
import com.apimarketplace.common.scaling.queue.DistributedPriorityQueue;
import com.apimarketplace.common.scaling.queue.InMemoryPriorityQueue;
import com.apimarketplace.common.scaling.queue.QueueMessage;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisExecutionQueueServiceTest {

    private final TrackingPriorityQueue queue = new TrackingPriorityQueue();
    private final InMemoryKeyValueStore keyValueStore = new InMemoryKeyValueStore();
    private final TrackingSemaphore semaphore = new TrackingSemaphore();
    private final InMemoryClaimStore claimStore = new InMemoryClaimStore();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<RedisExecutionQueueService> services = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (RedisExecutionQueueService service : services) {
            service.destroy();
        }
    }

    @Test
    @DisplayName("Synchronous caller can receive a result produced by another queue instance")
    void syncCallerReceivesResultFromAnotherInstance() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService producerTriggerService = mock(ReusableTriggerService.class);
        ReusableTriggerService consumerTriggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-cross-instance", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-cross-instance")).thenReturn(Optional.of(run));
        TriggerExecutionResult expected = TriggerExecutionResult.success(
                "run-cross-instance", "trigger:t", TriggerType.MANUAL, Set.of(), 7);
        when(consumerTriggerService.executeTriggerInternal(
                eq(run), eq("trigger:t"), eq(TriggerType.MANUAL), eq(Map.of("x", 1)), eq(false)))
                .thenReturn(expected);

        RedisExecutionQueueService producer = newService(producerTriggerService, runRepository, 1, 1, 5, false);
        newService(consumerTriggerService, runRepository, 1, 1, 5, true);

        TriggerExecutionResult result = producer.enqueueAndWait(
                run, "trigger:t", TriggerType.MANUAL, Map.of("x", 1), "PRO");

        assertTrue(result.success());
        assertEquals(7, result.epoch());
        verifyNoInteractions(producerTriggerService);
        verify(consumerTriggerService).executeTriggerInternal(
                eq(run), eq("trigger:t"), eq(TriggerType.MANUAL), eq(Map.of("x", 1)), eq(false));
    }

    @Test
    @DisplayName("Duplicate delivery of the same requestId executes only once")
    void duplicateRequestIdExecutesOnlyOnce() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-duplicate", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-duplicate")).thenReturn(Optional.of(run));
        CountDownLatch executed = new CountDownLatch(1);
        when(triggerService.executeTriggerInternal(any(), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    executed.countDown();
                    return TriggerExecutionResult.success("run-duplicate", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        newService(triggerService, runRepository, 2, 2, 5, true);

        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", "req-duplicate",
                true, Duration.ofSeconds(5), Clock.systemUTC());
        QueueMessage<QueuedExecutionMessage> first = queueMessage(message, Instant.now());
        QueueMessage<QueuedExecutionMessage> second = queueMessage(message, Instant.now().plusMillis(1));
        queue.push(first);
        queue.push(second);

        assertTrue(executed.await(3, TimeUnit.SECONDS), "one duplicate delivery should execute");
        waitUntil(() -> queue.size() == 0, "duplicate messages should be drained");

        verify(triggerService, times(1))
                .executeTriggerInternal(eq(run), eq("trigger:t"), eq(TriggerType.MANUAL), eq(Map.of()), eq(false));
    }

    @Test
    @DisplayName("Idle workers poll write-free (no semaphore acquire, no dequeue) and still wake up when work arrives")
    void idleWorkersSkipSemaphoreAndDequeueOnEmptyQueue() throws Exception {
        // Regression for the prod 2026-06-10 Redis write-storm: with an EMPTY queue,
        // every worker poll cycle wrote to Redis (semaphore ZADD/ZREM + DRR deficit
        // HSETs + reclaim-cursor HSET) -> ~700KB/s of AOF and a rewrite fork every
        // ~90s. The idle fast-path must keep empty polling strictly read-only.
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-idle", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-idle")).thenReturn(Optional.of(run));
        CountDownLatch executed = new CountDownLatch(1);
        when(triggerService.executeTriggerInternal(any(), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    executed.countDown();
                    return TriggerExecutionResult.success("run-idle", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        newService(triggerService, runRepository, 2, 2, 5, true);

        // Several poll cycles (pollIdleMs=10) against the empty queue.
        Thread.sleep(400);

        assertEquals(0, queue.dequeueCount(), "empty queue must never be dequeued (idle fast-path is read-only)");
        assertEquals(0, semaphore.acquireCount(), "no worker permit may be taken while the queue is empty");

        // The backoff is capped, so a message pushed after an idle period must still execute.
        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", "req-idle-wake",
                true, Duration.ofSeconds(5), Clock.systemUTC());
        queue.push(queueMessage(message, Instant.now()));

        assertTrue(executed.await(3, TimeUnit.SECONDS),
                "a message pushed after an idle period should still be executed (capped backoff)");
    }

    @Test
    @DisplayName("A draining instance stops dequeuing: the message stays in the shared queue for a live replica")
    void drainingInstanceDoesNotDequeue() throws Exception {
        // The shutdown drain waits for this instance's active executions to reach zero; a
        // worker that kept claiming work from the shared queue would refill that count from
        // other replicas' traffic until the drain timed out.
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-draining", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-draining")).thenReturn(Optional.of(run));
        com.apimarketplace.orchestrator.lifecycle.OrchestratorLifecycleGate gate =
                new com.apimarketplace.orchestrator.lifecycle.OrchestratorLifecycleGate(
                        null, "test", Duration.ofSeconds(60), Clock.systemUTC());
        gate.enterDraining();
        RedisExecutionQueueService service = newService(triggerService, runRepository, 2, 2, 5, true);
        service.setLifecycleGate(gate);

        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", "req-draining",
                true, Duration.ofSeconds(5), Clock.systemUTC());
        queue.push(queueMessage(message, Instant.now()));
        Thread.sleep(400);

        assertEquals(0, queue.dequeueCount(), "a draining instance must not claim new work");
        assertEquals(0, semaphore.acquireCount(), "a draining instance must not take a worker permit");
        assertEquals(1, queue.size(), "the message stays queued for another replica");
        verify(triggerService, never()).executeTriggerInternal(any(), anyString(), any(), any(), anyBoolean());
        // A draining worker holds its claim only for the instant it takes to read the gate.
        waitUntil(() -> service.getLocalActiveExecutions() == 0, "a draining idle worker must not count as busy");
    }

    @Test
    @DisplayName("A worker past the drain gate but not yet dequeued is counted, so the drain cannot see idle and cut the run it starts")
    void workerBetweenGateAndDequeueIsCounted() throws Exception {
        // Regression: the gate was read before anything was counted, so a worker that passed
        // it just before the drain flipped it was invisible until it had dequeued. The drain
        // saw idle and exited, destroy() interrupted the execution the worker then started,
        // and the reclaim of that message (executingAt already set) acked it unrun.
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-window", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-window")).thenReturn(Optional.of(run));
        CountDownLatch executed = new CountDownLatch(1);
        when(triggerService.executeTriggerInternal(any(), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    executed.countDown();
                    return TriggerExecutionResult.success("run-window", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        CountDownLatch release = new CountDownLatch(1);
        semaphore.holdAcquire = release;
        com.apimarketplace.orchestrator.lifecycle.OrchestratorLifecycleGate gate =
                new com.apimarketplace.orchestrator.lifecycle.OrchestratorLifecycleGate(
                        null, "test", Duration.ofSeconds(60), Clock.systemUTC());
        RedisExecutionQueueService service = newService(triggerService, runRepository, 1, 1, 5, false);
        service.setLifecycleGate(gate);
        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", "req-window",
                true, Duration.ofSeconds(5), Clock.systemUTC());
        queue.push(queueMessage(message, Instant.now()));
        ReflectionTestUtils.invokeMethod(service, "startWorkers");

        assertTrue(semaphore.acquireEntered.await(3, TimeUnit.SECONDS), "the worker should pass the gate");
        gate.enterDraining(); // the drain starts while the worker is between gate and dequeue

        assertEquals(1, service.getLocalActiveExecutions(),
                "a worker that passed the gate must be visible to the drain before it dequeues");
        release.countDown();
        assertTrue(executed.await(3, TimeUnit.SECONDS), "the claimed message still executes");
        waitUntil(() -> service.getLocalActiveExecutions() == 0, "released once the execution ends");
    }

    @Test
    @DisplayName("getLocalActiveExecutions counts an execution running on this instance and drops back to zero")
    void localActiveExecutionsTracksRunningWork() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-active", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-active")).thenReturn(Optional.of(run));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(triggerService.executeTriggerInternal(any(), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return TriggerExecutionResult.success("run-active", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        RedisExecutionQueueService service = newService(triggerService, runRepository, 2, 2, 5, true);
        assertEquals(0, service.getLocalActiveExecutions());

        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", "req-active",
                true, Duration.ofSeconds(5), Clock.systemUTC());
        queue.push(queueMessage(message, Instant.now()));

        assertTrue(started.await(3, TimeUnit.SECONDS), "the worker should start the execution");
        assertEquals(1, service.getLocalActiveExecutions());
        release.countDown();
        waitUntil(() -> service.getLocalActiveExecutions() == 0, "the count must return to zero once the execution ends");
    }

    @Test
    @DisplayName("A non-positive pollIdleMs is rejected (it would freeze the idle backoff into a hot spin)")
    void nonPositivePollIdleMsIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new RedisExecutionQueueService(
                        mock(ReusableTriggerService.class),
                        mock(WorkflowRunRepository.class),
                        new ExecutionQueueMetrics(new SimpleMeterRegistry()),
                        queue,
                        keyValueStore,
                        semaphore,
                        claimStore,
                        objectMapper,
                        1, 1, 5,
                        0,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        "test:exec",
                        Clock.systemUTC(),
                        false));
        assertTrue(ex.getMessage().contains("pollIdleMs"));
    }

    @Test
    @DisplayName("Duplicate delivery while original is running is left unacked")
    void runningDuplicateIsNotAcknowledgedOrExecuted() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-running-duplicate", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-running-duplicate")).thenReturn(Optional.of(run));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(triggerService.executeTriggerInternal(any(), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    firstStarted.countDown();
                    releaseFirst.await(3, TimeUnit.SECONDS);
                    return TriggerExecutionResult.success("run-running-duplicate", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        newService(triggerService, runRepository, 2, 2, 5, true);

        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", "req-running-duplicate",
                true, Duration.ofSeconds(5), Clock.systemUTC());
        queue.push(queueMessage(message, Instant.now()));
        assertTrue(firstStarted.await(3, TimeUnit.SECONDS), "first delivery should start execution");

        queue.push(queueMessage(message, Instant.now().plusMillis(1)));
        Thread.sleep(100);
        assertEquals(0, queue.acknowledgedCount(),
                "running duplicate should be left pending instead of ACK/XDEL while the first owner is active");

        releaseFirst.countDown();
        waitUntil(() -> queue.acknowledgedCount() == 1, "original delivery should ACK after completion");
        verify(triggerService, times(1)).executeTriggerInternal(any(), anyString(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("Global worker permits are shared across queue instances")
    void globalWorkerLimitIsSharedAcrossInstances() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        when(runRepository.findByRunIdPublic(anyString())).thenAnswer(inv ->
                Optional.of(mockRun(inv.getArgument(0), "tenant-a", "org-a", "OWNER")));
        CountDownLatch executed = new CountDownLatch(4);
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxActive = new AtomicInteger(0);
        when(triggerService.executeTriggerInternal(any(), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    int current = active.incrementAndGet();
                    maxActive.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(80);
                        WorkflowRunEntity run = inv.getArgument(0);
                        return TriggerExecutionResult.success(run.getRunIdPublic(), "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                    } finally {
                        active.decrementAndGet();
                        executed.countDown();
                    }
                });

        RedisExecutionQueueService instanceA = newService(triggerService, runRepository, 2, 1, 5, true);
        newService(triggerService, runRepository, 2, 1, 5, true);

        for (int i = 0; i < 4; i++) {
            TriggerExecutionResult ack = instanceA.enqueueAsync(
                    mockRun("run-global-" + i, "tenant-a", "org-a", "OWNER"),
                    "trigger:t", TriggerType.MANUAL, Map.of("i", i), "ENTERPRISE_ULTIMATE");
            assertTrue(ack.success());
        }

        assertTrue(executed.await(5, TimeUnit.SECONDS), "all executions should finish");
        assertTrue(maxActive.get() <= 1, "global semaphore must cap concurrency across instances");
    }

    @Test
    @DisplayName("Timed-out queued messages are cancelled and skipped by a later worker")
    void timeoutCancelsQueuedMessageBeforeLaterWorkerCanRunIt() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-timeout", "tenant-a", "org-a", "OWNER");
        RedisExecutionQueueService producer = newService(triggerService, runRepository, 1, 1, 1, false);

        TriggerExecutionResult result = producer.enqueueAndWait(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");

        assertFalse(result.success());
        assertTrue(result.message().contains("queue timeout"));

        newService(triggerService, runRepository, 1, 1, 1, true);
        waitUntil(() -> queue.size() == 0, "cancelled message should be drained without execution");

        verifyNoInteractions(triggerService);
        verifyNoInteractions(runRepository);
    }

    /**
     * Regression (prod 2026-09-25): the deadline bounds the WAIT, not the run. A run that started in
     * time but outlived the wait was reported "could not start within N minutes" while it kept
     * executing and completed its epoch: an ERROR per long run, a false timeout metric, and a manual
     * fire rolled back its counters. It must be reported as started and still running.
     */
    @Test
    @DisplayName("sync wait that runs out AFTER the worker started reports still-running, never could-not-start")
    void syncWaitReportsStillRunningAfterRunningStart() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-running-timeout", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-running-timeout")).thenReturn(Optional.of(run));
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        CountDownLatch executionFinished = new CountDownLatch(1);
        when(triggerService.executeTriggerInternal(eq(run), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    executionStarted.countDown();
                    releaseExecution.await(5, TimeUnit.SECONDS);
                    executionFinished.countDown();
                    return TriggerExecutionResult.success("run-running-timeout", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisExecutionQueueService service = newService(triggerService, runRepository, 1, 1, 1, true,
                keyValueStore, registry);

        TriggerExecutionResult result = service.enqueueAndWait(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");

        assertTrue(executionStarted.await(100, TimeUnit.MILLISECONDS),
                "worker should have started before the sync wait ran out");
        assertEquals(1.0, completedCount(registry, "still_running"));
        assertEquals(0.0, completedCount(registry, "timeout"));
        assertTrue(result.success(), "a started run is not a failure");
        assertEquals(TriggerExecutionResult.STILL_RUNNING_MESSAGE, result.message());
        assertEquals("run-running-timeout", result.runId());

        // The run was not cancelled: it finishes on its own after the caller stopped waiting.
        releaseExecution.countDown();
        assertTrue(executionFinished.await(5, TimeUnit.SECONDS), "the started run must run to completion");
        verify(triggerService, times(1)).executeTriggerInternal(eq(run), anyString(), any(), any(), eq(false));
    }

    /**
     * The deadline verdict must rest on the EXECUTION boundary, not on RUNNING: the ledger turns
     * RUNNING when a worker claims the message, before its cancel / expiry check, which can still
     * drop the message unexecuted. These drive settleAtDeadline on a hand-written ledger so every
     * interleaving is deterministic.
     */
    @Nested
    @DisplayName("settleAtDeadline - did the run's execution start?")
    class SettleAtDeadlineTests {

        private QueuedExecutionMessage message(RedisExecutionQueueService service, String requestId) {
            return QueuedExecutionMessage.fromRun(mockRun("run-" + requestId, "tenant-a", "org-a", "OWNER"),
                    "trigger:t", TriggerType.MANUAL, Map.of(), "TEAM", requestId, false,
                    Duration.ofSeconds(1), Clock.systemUTC());
        }

        private void ledger(RedisExecutionQueueService service, String requestId, Map<String, String> fields) {
            keyValueStore.hashPutAll(service.ledgerKey(requestId), fields, Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("execution boundary present: started, and the message is NOT cancelled")
        void boundaryPresentMeansStarted() {
            RedisExecutionQueueService service = newService(mock(ReusableTriggerService.class),
                    mock(WorkflowRunRepository.class), 1, 1, 1, false);
            ledger(service, "req-started", Map.of("status", "RUNNING",
                    RedisExecutionQueueService.LEDGER_EXECUTING_AT, "2026-09-25T16:00:00Z"));

            Map<String, String> settled = service.settleAtDeadline(message(service, "req-started"),
                    keyValueStore.hashGetAll(service.ledgerKey("req-started")));

            assertTrue(settled.containsKey(RedisExecutionQueueService.LEDGER_EXECUTING_AT));
            assertFalse(keyValueStore.exists(service.cancelKey("req-started")));
        }

        @Test
        @DisplayName("claimed (RUNNING) but the worker then drops it as cancelled: NOT started")
        void claimedThenCancelledByWorkerIsNotStarted() throws Exception {
            RedisExecutionQueueService service = newService(mock(ReusableTriggerService.class),
                    mock(WorkflowRunRepository.class), 1, 1, 1, false);
            ledger(service, "req-dropped", Map.of("status", "RUNNING"));
            Thread worker = new Thread(() -> {
                sleepQuietly(150);
                ledger(service, "req-dropped", Map.of("status", "CANCELLED"));
            });
            worker.start();

            Map<String, String> settled = service.settleAtDeadline(message(service, "req-dropped"),
                    keyValueStore.hashGetAll(service.ledgerKey("req-dropped")));
            worker.join();

            assertEquals("CANCELLED", settled.get("status"));
            assertFalse(settled.containsKey(RedisExecutionQueueService.LEDGER_EXECUTING_AT));
            assertTrue(keyValueStore.exists(service.cancelKey("req-dropped")),
                    "the caller must signal the cancel so the claiming worker drops the message");
        }

        @Test
        @DisplayName("claimed (RUNNING) and the worker passed its check first: started")
        void claimedThenExecutingIsStarted() throws Exception {
            RedisExecutionQueueService service = newService(mock(ReusableTriggerService.class),
                    mock(WorkflowRunRepository.class), 1, 1, 1, false);
            ledger(service, "req-late-start", Map.of("status", "RUNNING"));
            Thread worker = new Thread(() -> {
                sleepQuietly(150);
                keyValueStore.hashPut(service.ledgerKey("req-late-start"),
                        RedisExecutionQueueService.LEDGER_EXECUTING_AT, "2026-09-25T16:05:00Z");
            });
            worker.start();

            Map<String, String> settled = service.settleAtDeadline(message(service, "req-late-start"),
                    keyValueStore.hashGetAll(service.ledgerKey("req-late-start")));
            worker.join();

            assertTrue(settled.containsKey(RedisExecutionQueueService.LEDGER_EXECUTING_AT));
        }

        @Test
        @DisplayName("claimed (RUNNING) and the worker stays silent past the grace: NOT started")
        void silentClaimIsNotStarted() {
            RedisExecutionQueueService service = newService(mock(ReusableTriggerService.class),
                    mock(WorkflowRunRepository.class), 1, 1, 1, false);
            ledger(service, "req-silent", Map.of("status", "RUNNING"));

            long before = System.nanoTime();
            Map<String, String> settled = service.settleAtDeadline(message(service, "req-silent"),
                    keyValueStore.hashGetAll(service.ledgerKey("req-silent")));
            long waitedMs = (System.nanoTime() - before) / 1_000_000;

            assertFalse(settled.containsKey(RedisExecutionQueueService.LEDGER_EXECUTING_AT));
            assertTrue(waitedMs >= RedisExecutionQueueService.START_VERDICT_GRACE.toMillis() - 50,
                    "the caller waits for the worker's verdict, bounded by the grace");
            assertTrue(waitedMs < RedisExecutionQueueService.START_VERDICT_GRACE.toMillis() + 2_000);
        }

        private void sleepQuietly(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @DisplayName("worker writes the execution boundary before executing, never for a message it drops")
    void workerWritesExecutionBoundaryOnlyWhenItExecutes() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-boundary", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-boundary")).thenReturn(Optional.of(run));
        AtomicReference<Map<String, String>> ledgerDuringExecution = new AtomicReference<>();
        List<RedisExecutionQueueService> holder = new ArrayList<>();
        when(triggerService.executeTriggerInternal(eq(run), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    String requestId = ExecutionQueueRequestContext.currentRequestId();
                    ledgerDuringExecution.set(keyValueStore.hashGetAll(holder.get(0).ledgerKey(requestId)));
                    return TriggerExecutionResult.success("run-boundary", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        RedisExecutionQueueService service = newService(triggerService, runRepository, 1, 1, 5, true);
        holder.add(service);

        TriggerExecutionResult result = service.enqueueAndWait(run, "trigger:t", TriggerType.MANUAL, Map.of(), "TEAM");

        assertTrue(result.success());
        assertNotNull(ledgerDuringExecution.get());
        assertTrue(ledgerDuringExecution.get().containsKey(RedisExecutionQueueService.LEDGER_EXECUTING_AT),
                "the boundary must be visible while the run executes");
    }

    /**
     * The run finished in the instant between the loop's result read and its deadline check: the
     * deadline block sees DONE, and one more pass must return the REAL result, not still-running
     * and not a timeout. The store hides the result for exactly that first post-deadline read, so
     * the interleaving is forced rather than hoped for.
     */
    @Test
    @DisplayName("run that finished right at the deadline returns its real result (one re-read), counted once")
    void finishedRightAtDeadlineReturnsRealResult() throws Exception {
        String requestId = "req-finished-at-deadline";
        AtomicReference<Instant> deadline = new AtomicReference<>();
        AtomicInteger hiddenPostDeadlineReads = new AtomicInteger(1);
        InMemoryKeyValueStore store = new InMemoryKeyValueStore() {
            @Override
            public Optional<String> get(String key) {
                if (key.endsWith(":result:" + requestId)) {
                    Instant d = deadline.get();
                    if (d == null || Instant.now().isBefore(d)) {
                        return Optional.empty();
                    }
                    if (hiddenPostDeadlineReads.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        return Optional.empty();
                    }
                }
                return super.get(key);
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisExecutionQueueService service = newService(mock(ReusableTriggerService.class),
                mock(WorkflowRunRepository.class), 1, 1, 1, false, store, registry);
        WorkflowRunEntity run = mockRun("run-finished", "tenant-a", "org-a", "OWNER");
        TriggerExecutionResult real = TriggerExecutionResult.success("run-finished", "trigger:t", TriggerType.MANUAL, Set.of(), 4);
        store.set("test:exec:result:" + requestId, objectMapper.writeValueAsString(real), Duration.ofMinutes(5));

        // Play the worker once the message is queued (enqueue writes the ledger PENDING first):
        // learn the caller's exact deadline, then leave the ledger DONE with its execution boundary.
        Thread worker = new Thread(() -> {
            Optional<QueueMessage<QueuedExecutionMessage>> queued = Optional.empty();
            while (queued.isEmpty()) {
                queued = queue.dequeue("test-worker");
            }
            deadline.set(queued.get().getPayload().expiresAt());
            store.hashPutAll(service.ledgerKey(requestId), Map.of("status", "DONE",
                    RedisExecutionQueueService.LEDGER_EXECUTING_AT, Instant.now().toString()), Duration.ofMinutes(5));
        });
        worker.start();

        TriggerExecutionResult result = service.enqueueAndWait(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", requestId);
        worker.join();

        assertTrue(result.success());
        assertEquals(4, result.epoch(), "the real result, not still-running (epoch -1)");
        assertEquals(0, hiddenPostDeadlineReads.get(), "the deadline block must have been reached");
        assertEquals(1.0, completedCount(registry, "success"));
        assertEquals(0.0, completedCount(registry, "still_running"));
        assertEquals(0.0, completedCount(registry, "timeout"));
    }

    private static double completedCount(SimpleMeterRegistry registry, String outcome) {
        io.micrometer.core.instrument.Counter counter = registry.find(ExecutionQueueMetrics.COMPLETED_TOTAL)
                .tags("outcome", outcome, "tenant", ExecutionQueueMetrics.AGGREGATE).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("Worker rehydrates the run and binds the run organization scope")
    void workerUsesRehydratedRunOrganizationScope() {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-org", "tenant-a", "org-run", "ADMIN");
        when(runRepository.findByRunIdPublic("run-org")).thenReturn(Optional.of(run));
        AtomicReference<String> observedOrg = new AtomicReference<>();
        when(triggerService.executeTriggerInternal(eq(run), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    observedOrg.set(TenantResolver.currentRequestOrganizationId());
                    return TriggerExecutionResult.success("run-org", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });

        RedisExecutionQueueService producer = newService(mock(ReusableTriggerService.class), runRepository, 1, 1, 5, false);
        newService(triggerService, runRepository, 1, 1, 5, true);

        TriggerExecutionResult result = producer.enqueueAndWait(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "TEAM");

        assertTrue(result.success());
        assertEquals("org-run", observedOrg.get());
    }

    @Test
    @DisplayName("Organization mismatch between queued message and rehydrated run fails closed")
    void organizationMismatchFailsClosedWithoutExecuting() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity queuedRun = mockRun("run-mismatch", "tenant-a", "org-old", "OWNER");
        WorkflowRunEntity rehydratedRun = mockRun("run-mismatch", "tenant-a", "org-new", "OWNER");
        when(runRepository.findByRunIdPublic("run-mismatch")).thenReturn(Optional.of(rehydratedRun));
        newService(triggerService, runRepository, 1, 1, 5, true);

        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                queuedRun, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", "req-mismatch",
                true, Duration.ofSeconds(5), Clock.systemUTC());
        queue.push(queueMessage(message, Instant.now()));

        waitUntil(() -> queue.size() == 0, "mismatched message should be drained");

        verify(triggerService, never()).executeTriggerInternal(any(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Reclaimed message with durable started epoch is acknowledged without duplicate execution")
    void durableStartedClaimPreventsDuplicateExecutionAfterRedisLeaseExpires() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-durable-started", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-durable-started")).thenReturn(Optional.of(run));
        claimStore.seedRunningWithEpoch("req-durable-started", "run-durable-started", "trigger:t", 4);
        newService(triggerService, runRepository, 1, 1, 5, true);

        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE", "req-durable-started",
                true, Duration.ofSeconds(5), Clock.systemUTC());
        queue.push(queueMessage(message, Instant.now()));

        waitUntil(() -> queue.acknowledgedCount() == 1, "durably-started duplicate should be acknowledged");
        verify(triggerService, never()).executeTriggerInternal(any(), anyString(), any(), any(), anyBoolean());
    }

    private RedisExecutionQueueService newService(
            ReusableTriggerService triggerService,
            WorkflowRunRepository runRepository,
            int workerThreads,
            int globalWorkerPermits,
            int timeoutSeconds,
            boolean startWorkers) {
        return newService(triggerService, runRepository, workerThreads, globalWorkerPermits, timeoutSeconds,
                startWorkers, keyValueStore, new SimpleMeterRegistry());
    }

    private RedisExecutionQueueService newService(
            ReusableTriggerService triggerService,
            WorkflowRunRepository runRepository,
            int workerThreads,
            int globalWorkerPermits,
            int timeoutSeconds,
            boolean startWorkers,
            InMemoryKeyValueStore store,
            SimpleMeterRegistry registry) {
        RedisExecutionQueueService service = new RedisExecutionQueueService(
                triggerService,
                runRepository,
                new ExecutionQueueMetrics(registry),
                queue,
                store,
                semaphore,
                claimStore,
                objectMapper,
                workerThreads,
                globalWorkerPermits,
                timeoutSeconds,
                10,
                Duration.ofSeconds(60),
                Duration.ofSeconds(60),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                "test:exec",
                Clock.systemUTC(),
                startWorkers);
        services.add(service);
        return service;
    }

    private QueueMessage<QueuedExecutionMessage> queueMessage(QueuedExecutionMessage message, Instant createdAt) {
        return new QueueMessage<>(
                message.requestId(),
                message,
                PlanPriorityMapper.toRedisPriorityTier(message.planPriority()),
                createdAt,
                Map.of());
    }

    private WorkflowRunEntity mockRun(String runId, String tenantId, String organizationId, String organizationRole) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getRunIdPublic()).thenReturn(runId);
        when(run.getTenantId()).thenReturn(tenantId);
        when(run.getOrganizationId()).thenReturn(organizationId);
        when(run.getOrganizationRole()).thenReturn(organizationRole);
        return run;
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

    private static final class TrackingPriorityQueue extends InMemoryPriorityQueue<QueuedExecutionMessage> {
        private final AtomicInteger acknowledged = new AtomicInteger(0);
        private final AtomicInteger dequeueCalls = new AtomicInteger(0);

        @Override
        public Optional<QueueMessage<QueuedExecutionMessage>> dequeue(String consumerId) {
            dequeueCalls.incrementAndGet();
            return super.dequeue(consumerId);
        }

        int dequeueCount() {
            return dequeueCalls.get();
        }

        @Override
        public void acknowledge(QueueMessage<QueuedExecutionMessage> message) {
            acknowledged.incrementAndGet();
            super.acknowledge(message);
        }

        int acknowledgedCount() {
            return acknowledged.get();
        }
    }

    private static final class TrackingSemaphore extends InMemorySemaphore {
        private final AtomicInteger acquires = new AtomicInteger(0);
        /** When set, a worker entering tryAcquire (i.e. already past the drain gate) parks here. */
        volatile CountDownLatch holdAcquire;
        final CountDownLatch acquireEntered = new CountDownLatch(1);

        @Override
        public boolean tryAcquire(String key, int maxPermits, String ownerId) {
            acquires.incrementAndGet();
            CountDownLatch hold = holdAcquire;
            if (hold != null) {
                acquireEntered.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.tryAcquire(key, maxPermits, ownerId);
        }

        int acquireCount() {
            return acquires.get();
        }
    }

    private static final class InMemoryClaimStore implements ExecutionQueueClaimStore {
        private final ConcurrentMap<String, ClaimRecord> claims = new ConcurrentHashMap<>();

        @Override
        public ClaimRecord claimForExecution(QueuedExecutionMessage message, String ownerId) {
            ClaimRecord existing = claims.putIfAbsent(message.requestId(),
                    new ClaimRecord(message.requestId(), STATUS_RUNNING, ownerId, null, null, null, true));
            if (existing == null) {
                return claims.get(message.requestId());
            }
            if (STATUS_RUNNING.equals(existing.status()) && existing.epoch() == null) {
                ClaimRecord updated = new ClaimRecord(
                        existing.requestId(), STATUS_RUNNING, ownerId, null, null, null, true);
                claims.put(message.requestId(), updated);
                return updated;
            }
            return existing;
        }

        @Override
        public void markEpochStarted(String requestId, String runIdPublic, String triggerId, int epoch) {
            claims.computeIfPresent(requestId, (ignored, existing) -> new ClaimRecord(
                    existing.requestId(), existing.status(), existing.ownerId(), epoch,
                    existing.result(), existing.message(), existing.newlyClaimed()));
        }

        @Override
        public void complete(QueuedExecutionMessage message, String status, TriggerExecutionResult result) {
            claims.put(message.requestId(), new ClaimRecord(
                    message.requestId(), status, null, null, result,
                    result != null ? result.message() : null, false));
        }

        @Override
        public int purgeCompletedBefore(Instant cutoff, int limit) {
            return 0;
        }

        void seedRunningWithEpoch(String requestId, String runIdPublic, String triggerId, int epoch) {
            claims.put(requestId, new ClaimRecord(
                    requestId, STATUS_RUNNING, "previous-owner", epoch, null, null, false));
        }
    }
}
