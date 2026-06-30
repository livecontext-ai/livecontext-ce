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
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("Synchronous wait times out even after a worker has marked the message running")
    void syncWaitTimesOutAfterRunningStart() throws Exception {
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        ReusableTriggerService triggerService = mock(ReusableTriggerService.class);
        WorkflowRunEntity run = mockRun("run-running-timeout", "tenant-a", "org-a", "OWNER");
        when(runRepository.findByRunIdPublic("run-running-timeout")).thenReturn(Optional.of(run));
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        when(triggerService.executeTriggerInternal(eq(run), anyString(), any(), any(), eq(false)))
                .thenAnswer(inv -> {
                    executionStarted.countDown();
                    releaseExecution.await(3, TimeUnit.SECONDS);
                    return TriggerExecutionResult.success("run-running-timeout", "trigger:t", TriggerType.MANUAL, Set.of(), 1);
                });
        RedisExecutionQueueService service = newService(triggerService, runRepository, 1, 1, 1, true);

        TriggerExecutionResult result = service.enqueueAndWait(
                run, "trigger:t", TriggerType.MANUAL, Map.of(), "FREE");

        assertTrue(executionStarted.await(100, TimeUnit.MILLISECONDS),
                "worker should have started before the sync caller timed out");
        assertFalse(result.success());
        assertTrue(result.message().contains("queue timeout"));
        releaseExecution.countDown();
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
        RedisExecutionQueueService service = new RedisExecutionQueueService(
                triggerService,
                runRepository,
                new ExecutionQueueMetrics(new SimpleMeterRegistry()),
                queue,
                keyValueStore,
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

        @Override
        public boolean tryAcquire(String key, int maxPermits, String ownerId) {
            acquires.incrementAndGet();
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
