package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.common.event.KeyValueStore;
import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.common.scaling.queue.DistributedPriorityQueue;
import com.apimarketplace.common.scaling.queue.QueueMessage;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis-backed execution queue for horizontal orchestrator scaling.
 *
 * <p>All requests go through the distributed queue in this mode. Workers rehydrate
 * runs by public id, enforce a distributed worker semaphore, and use a Redis-backed
 * ledger to make duplicate Redis Stream deliveries no-op instead of opening a
 * second trigger epoch.
 */
@Service
@ConditionalOnProperty(name = "workflow.execution-queue.backend", havingValue = "redis")
public class RedisExecutionQueueService implements ExecutionQueue, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(RedisExecutionQueueService.class);

    private static final String DEFAULT_NAMESPACE = "orch:queue:exec";
    private static final String WORKER_SEMAPHORE_KEY = "execution_queue:workers";

    /**
     * Ceiling for the idle-poll backoff in {@link #workerLoop(String)}. When the
     * queue is empty each worker doubles its sleep from {@code pollIdleMs} up to
     * this cap, and resets as soon as work shows up. Bounds the added pickup
     * latency for the first message after an idle period.
     */
    static final long IDLE_BACKOFF_MAX_MS = 800;
    private static final String IDEMPOTENCY_SEMAPHORE_PREFIX = "execution_queue:idempotency:";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    public static final String QUEUE_UNAVAILABLE_MESSAGE = "Execution queue unavailable, please retry";

    private final ReusableTriggerService triggerService;
    private final WorkflowRunRepository runRepository;
    private final ExecutionQueueMetrics metrics;
    private final DistributedPriorityQueue<QueuedExecutionMessage> queue;
    private final KeyValueStore keyValueStore;
    private final DistributedSemaphore distributedSemaphore;
    private final ExecutionQueueClaimStore claimStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String namespace;
    private final String instanceId;
    private final int workerThreads;
    private final int globalWorkerPermits;
    private final int timeoutSeconds;
    private final long pollIdleMs;
    private final Duration ledgerTtl;
    private final Duration resultTtl;
    private final Duration workerPermitTtl;
    private final Duration executionLeaseTtl;
    private final ExecutorService workerPool;
    private final ScheduledExecutorService heartbeatPool;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger activeExecutions = new AtomicInteger(0);
    private final ConcurrentMap<String, Boolean> activeWorkerPermits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, QueuedExecutionMessage> activeExecutionLeases = new ConcurrentHashMap<>();

    @Autowired
    public RedisExecutionQueueService(
            @Lazy ReusableTriggerService triggerService,
            WorkflowRunRepository runRepository,
            ExecutionQueueMetrics metrics,
            @Qualifier("orchestratorExecutionPriorityQueue")
            DistributedPriorityQueue<QueuedExecutionMessage> queue,
            KeyValueStore keyValueStore,
            DistributedSemaphore distributedSemaphore,
            ExecutionQueueClaimStore claimStore,
            ObjectMapper objectMapper,
            @Value("${workflow.execution-queue.worker-threads:20}") int workerThreads,
            @Value("${workflow.execution-queue.global-worker-permits:${workflow.execution-queue.worker-threads:20}}")
            int globalWorkerPermits,
            @Value("${workflow.execution-queue.timeout-seconds:300}") int timeoutSeconds,
            @Value("${workflow.execution-queue.redis.poll-idle-ms:50}") long pollIdleMs,
            @Value("${workflow.execution-queue.redis.ledger-ttl-seconds:86400}") long ledgerTtlSeconds,
            @Value("${workflow.execution-queue.redis.result-ttl-seconds:900}") long resultTtlSeconds,
            @Value("${workflow.execution-queue.redis.worker-permit-ttl-seconds:120}") long workerPermitTtlSeconds,
            @Value("${workflow.execution-queue.redis.execution-lease-ttl-seconds:120}") long executionLeaseTtlSeconds,
            @Value("${workflow.execution-queue.redis.namespace:orch:queue:exec}") String namespace) {

        this(triggerService, runRepository, metrics, queue, keyValueStore, distributedSemaphore, claimStore,
                objectMapper, workerThreads, globalWorkerPermits, timeoutSeconds, pollIdleMs,
                Duration.ofSeconds(ledgerTtlSeconds), Duration.ofSeconds(resultTtlSeconds),
                Duration.ofSeconds(workerPermitTtlSeconds), Duration.ofSeconds(executionLeaseTtlSeconds),
                namespace, Clock.systemUTC(), true);
    }

    RedisExecutionQueueService(
            ReusableTriggerService triggerService,
            WorkflowRunRepository runRepository,
            ExecutionQueueMetrics metrics,
            DistributedPriorityQueue<QueuedExecutionMessage> queue,
            KeyValueStore keyValueStore,
            DistributedSemaphore distributedSemaphore,
            ExecutionQueueClaimStore claimStore,
            ObjectMapper objectMapper,
            int workerThreads,
            int globalWorkerPermits,
            int timeoutSeconds,
            long pollIdleMs,
            Duration ledgerTtl,
            Duration resultTtl,
            Duration workerPermitTtl,
            Duration executionLeaseTtl,
            String namespace,
            Clock clock,
            boolean startWorkers) {

        if (workerThreads <= 0) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        if (globalWorkerPermits <= 0) {
            throw new IllegalArgumentException("globalWorkerPermits must be positive");
        }
        if (pollIdleMs <= 0) {
            // 0 would freeze the idle backoff at 0 (0*2=0) and turn the idle
            // fast-path into a hot spin of XLEN peeks.
            throw new IllegalArgumentException("pollIdleMs must be positive");
        }
        this.triggerService = triggerService;
        this.runRepository = runRepository;
        this.metrics = metrics;
        this.queue = queue;
        this.keyValueStore = keyValueStore;
        this.distributedSemaphore = distributedSemaphore;
        this.claimStore = claimStore;
        this.objectMapper = objectMapper;
        this.workerThreads = workerThreads;
        this.globalWorkerPermits = globalWorkerPermits;
        this.timeoutSeconds = timeoutSeconds;
        this.pollIdleMs = pollIdleMs;
        this.ledgerTtl = ledgerTtl;
        this.resultTtl = resultTtl;
        this.workerPermitTtl = workerPermitTtl != null ? workerPermitTtl : Duration.ofSeconds(120);
        this.executionLeaseTtl = executionLeaseTtl != null ? executionLeaseTtl : Duration.ofSeconds(120);
        this.namespace = (namespace == null || namespace.isBlank()) ? DEFAULT_NAMESPACE : namespace;
        this.clock = clock;
        this.instanceId = UUID.randomUUID().toString();
        this.workerPool = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r, "redis-exec-queue-worker-" + instanceId.substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        this.heartbeatPool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redis-exec-queue-heartbeat");
            t.setDaemon(true);
            return t;
        });

        metrics.bindQueueGauges(this::getQueueSize, this::getAvailableWorkers, activeExecutions::get);

        if (startWorkers) {
            startWorkers();
        }
        logger.info("[RedisExecutionQueue] Started with localWorkers={}, globalPermits={}, timeout={}s",
                workerThreads, globalWorkerPermits, timeoutSeconds);
    }

    @Override
    public TriggerExecutionResult enqueueAndWait(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan) {
        return enqueueAndWait(run, triggerId, triggerType, payload, userPlan, null);
    }

    @Override
    public TriggerExecutionResult enqueueAndWait(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan,
            String requestId) {

        if (run == null) {
            return TriggerExecutionResult.failure(null, triggerId, triggerType, "Workflow run is required");
        }

        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, triggerId, triggerType, payload, userPlan, stableOrRandomRequestId(requestId),
                false, Duration.ofSeconds(timeoutSeconds), clock);

        TriggerExecutionResult enqueueFailure = enqueueDistributed(message);
        if (enqueueFailure != null) {
            return enqueueFailure;
        }

        return waitForResult(message);
    }

    @Override
    public TriggerExecutionResult enqueueAsync(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan) {
        return enqueueAsync(run, triggerId, triggerType, payload, userPlan, null);
    }

    @Override
    public TriggerExecutionResult enqueueAsync(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan,
            String requestId) {

        if (run == null) {
            return TriggerExecutionResult.failure(null, triggerId, triggerType, "Workflow run is required");
        }

        QueuedExecutionMessage message = QueuedExecutionMessage.fromRun(
                run, triggerId, triggerType, payload, userPlan, stableOrRandomRequestId(requestId),
                true, Duration.ofSeconds(timeoutSeconds), clock);

        TriggerExecutionResult enqueueFailure = enqueueDistributed(message);
        if (enqueueFailure != null) {
            return enqueueFailure;
        }
        return TriggerExecutionResult.accepted(run.getRunIdPublic(), triggerId, triggerType);
    }

    private TriggerExecutionResult enqueueDistributed(QueuedExecutionMessage message) {
        String tenantId = message.tenantId();
        String userPlan = message.userPlan();
        if (!isReadyForEnqueue()) {
            TriggerExecutionResult failure = TriggerExecutionResult.failure(
                    message.runIdPublic(), message.triggerId(), message.triggerType(),
                    QUEUE_UNAVAILABLE_MESSAGE);
            metrics.recordCompleted(userPlan, tenantId, ExecutionQueueMetrics.OUTCOME_FAILURE);
            return failure;
        }

        try {
            metrics.recordEnqueued(userPlan, tenantId, ExecutionQueueMetrics.PATH_SLOW);
            markLedger(message, STATUS_PENDING, null, null);
            queue.push(toQueueMessage(message));
            return null;
        } catch (Exception e) {
            logger.error("[RedisExecutionQueue] Failed to enqueue runId={}, requestId={}: {}",
                    message.runIdPublic(), message.requestId(), e.getMessage(), e);
            TriggerExecutionResult failure = TriggerExecutionResult.failure(
                    message.runIdPublic(), message.triggerId(), message.triggerType(),
                    QUEUE_UNAVAILABLE_MESSAGE);
            safeWriteResultIfNeeded(message, failure);
            safeMarkLedger(message, STATUS_FAILED, null, failure);
            metrics.recordCompleted(userPlan, tenantId, ExecutionQueueMetrics.OUTCOME_FAILURE);
            return failure;
        }
    }

    private QueueMessage<QueuedExecutionMessage> toQueueMessage(QueuedExecutionMessage message) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("runIdPublic", message.runIdPublic());
        metadata.put("tenantId", message.tenantId());
        metadata.put("fireAndForget", String.valueOf(message.fireAndForget()));
        return new QueueMessage<>(
                message.requestId(),
                message,
                PlanPriorityMapper.toRedisPriorityTier(message.planPriority()),
                message.enqueuedAt(),
                metadata);
    }

    private TriggerExecutionResult waitForResult(QueuedExecutionMessage message) {
        Instant deadline = message.expiresAt();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Optional<String> encoded = keyValueStore.get(resultKey(message.requestId()));
                if (encoded.isPresent()) {
                    TriggerExecutionResult result = objectMapper.readValue(encoded.get(), TriggerExecutionResult.class);
                    keyValueStore.delete(resultKey(message.requestId()));
                    metrics.recordCompleted(message.userPlan(), message.tenantId(), outcomeOf(result));
                    return result;
                }

                Map<String, String> ledger = keyValueStore.hashGetAll(ledgerKey(message.requestId()));
                String status = ledger.get("status");
                if (STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status)) {
                    TriggerExecutionResult failure = TriggerExecutionResult.failure(
                            message.runIdPublic(), message.triggerId(), message.triggerType(),
                            ledger.getOrDefault("message", "Execution did not complete"));
                    metrics.recordCompleted(message.userPlan(), message.tenantId(), outcomeOf(failure));
                    return failure;
                }

                if (!Instant.now(clock).isBefore(deadline)) {
                    if (!STATUS_RUNNING.equals(status)) {
                        cancelQueuedMessage(message);
                    }
                    String normalizedPlan = message.userPlan() != null ? message.userPlan().toUpperCase() : "FREE";
                    String timeoutMessage = "Execution queue timeout: your workflow could not start within "
                            + (timeoutSeconds / 60) + " minutes. Current plan: " + normalizedPlan
                            + ". Upgrade your plan for higher execution priority.";
                    TriggerExecutionResult timeout = TriggerExecutionResult.failure(
                            message.runIdPublic(), message.triggerId(), message.triggerType(), timeoutMessage);
                    metrics.recordCompleted(message.userPlan(), message.tenantId(), ExecutionQueueMetrics.OUTCOME_TIMEOUT);
                    return timeout;
                }
            } catch (Exception e) {
                logger.error("[RedisExecutionQueue] Failed waiting for result requestId={}: {}",
                        message.requestId(), e.getMessage(), e);
                TriggerExecutionResult failure = TriggerExecutionResult.failure(
                        message.runIdPublic(), message.triggerId(), message.triggerType(),
                        "Execution queue result store unavailable, please retry");
                metrics.recordCompleted(message.userPlan(), message.tenantId(), ExecutionQueueMetrics.OUTCOME_FAILURE);
                return failure;
            }
            sleepQuietly(pollIdleMs);
        }

        Thread.currentThread().interrupt();
        cancelQueuedMessage(message);
        TriggerExecutionResult interrupted = TriggerExecutionResult.failure(
                message.runIdPublic(), message.triggerId(), message.triggerType(), "Execution interrupted");
        metrics.recordCompleted(message.userPlan(), message.tenantId(), ExecutionQueueMetrics.OUTCOME_CANCELLED);
        return interrupted;
    }

    private void cancelQueuedMessage(QueuedExecutionMessage message) {
        keyValueStore.set(cancelKey(message.requestId()), "1", ledgerTtl);
        Map<String, String> ledger = keyValueStore.hashGetAll(ledgerKey(message.requestId()));
        String status = ledger.get("status");
        if (!STATUS_RUNNING.equals(status) && !isFinalStatus(status)) {
            markLedger(message, STATUS_CANCELLED, null,
                    TriggerExecutionResult.failure(message.runIdPublic(), message.triggerId(), message.triggerType(),
                            "Execution was cancelled before start"));
        }
    }

    private void startWorkers() {
        for (int i = 0; i < workerThreads; i++) {
            int workerIndex = i;
            workerPool.submit(() -> workerLoop("consumer-" + instanceId + "-" + workerIndex));
        }
        heartbeatPool.scheduleAtFixedRate(this::heartbeatWorkerPermits, 30, 30, TimeUnit.SECONDS);
    }

    private void workerLoop(String consumerId) {
        long idleSleepMs = pollIdleMs;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            String workerOwnerId = consumerId + ":" + UUID.randomUUID();
            boolean permitHeld = false;
            boolean activeCounted = false;
            try {
                // Idle fast-path - peeking the stream lengths is READ-ONLY. Without
                // it every poll cycle WRITES to Redis even with an empty queue
                // (semaphore ZADD/ZREM + DRR deficit HSETs + reclaim-cursor HSET):
                // at 20 workers x 50ms x 2 pods that idle bookkeeping wrote
                // ~700KB/s of AOF and forced an AOF rewrite fork every ~90s in
                // prod, degrading every client of the shared Redis (2026-06-10).
                // XLEN counts unacked entries too (ack deletes them), so pending
                // messages from a dead consumer still wake the reclaim path.
                if (queue.size() == 0) {
                    sleepQuietly(idleSleepMs);
                    idleSleepMs = Math.min(idleSleepMs * 2, IDLE_BACKOFF_MAX_MS);
                    continue;
                }
                idleSleepMs = pollIdleMs;
                if (!distributedSemaphore.tryAcquire(WORKER_SEMAPHORE_KEY, globalWorkerPermits, workerOwnerId,
                        workerPermitTtl)) {
                    sleepQuietly(pollIdleMs);
                    continue;
                }
                permitHeld = true;

                Optional<QueueMessage<QueuedExecutionMessage>> next = queue.dequeue(consumerId);
                if (next.isEmpty()) {
                    sleepQuietly(pollIdleMs);
                    continue;
                }

                activeWorkerPermits.put(workerOwnerId, Boolean.TRUE);
                activeExecutions.incrementAndGet();
                activeCounted = true;
                processMessage(next.get(), workerOwnerId);
            } catch (Exception e) {
                logger.error("[RedisExecutionQueue] Worker loop error: {}", e.getMessage(), e);
                // Without this, a queue impl whose size()/dequeue throws on every
                // call would turn the loop into a hot log-spamming spin.
                sleepQuietly(pollIdleMs);
            } finally {
                if (activeCounted) {
                    activeExecutions.decrementAndGet();
                    activeWorkerPermits.remove(workerOwnerId);
                }
                if (permitHeld) {
                    distributedSemaphore.release(WORKER_SEMAPHORE_KEY, workerOwnerId);
                }
            }
        }
    }

    private void processMessage(QueueMessage<QueuedExecutionMessage> queueMessage, String ownerId) {
        QueuedExecutionMessage message = queueMessage.getPayload();
        boolean shouldAcknowledge = false;
        try {
            ClaimDecision decision = claimForExecution(message, ownerId);
            if (decision == ClaimDecision.ACK_WITHOUT_EXECUTION) {
                shouldAcknowledge = true;
                return;
            }
            if (decision == ClaimDecision.LEAVE_PENDING) {
                return;
            }

            activeExecutionLeases.put(ownerId, message);
            TriggerExecutionResult result;
            if (message.isExpired(clock) || keyValueStore.exists(cancelKey(message.requestId()))) {
                result = TriggerExecutionResult.failure(
                        message.runIdPublic(), message.triggerId(), message.triggerType(),
                        "Execution was cancelled before start");
                shouldAcknowledge = completeExecution(message, STATUS_CANCELLED, ownerId, result);
                return;
            }

            long waitMs = Duration.between(message.enqueuedAt(), Instant.now(clock)).toMillis();
            metrics.recordWaitMs(message.userPlan(), message.tenantId(), waitMs);
            result = executeRehydrated(message);
            shouldAcknowledge = completeExecution(
                    message, result.success() ? STATUS_DONE : STATUS_FAILED, ownerId, result);
        } catch (Exception e) {
            logger.error("[RedisExecutionQueue] Worker execution failed for requestId={}: {}",
                    message.requestId(), e.getMessage(), e);
            TriggerExecutionResult failure = TriggerExecutionResult.failure(
                    message.runIdPublic(), message.triggerId(), message.triggerType(),
                    "Execution failed: " + e.getMessage());
            shouldAcknowledge = completeExecution(message, STATUS_FAILED, ownerId, failure);
        } finally {
            if (shouldAcknowledge) {
                queue.acknowledge(queueMessage);
            }
            activeExecutionLeases.remove(ownerId);
        }
    }

    private ClaimDecision claimForExecution(QueuedExecutionMessage message, String ownerId) {
        message.validateForExecution();

        Map<String, String> ledger = keyValueStore.hashGetAll(ledgerKey(message.requestId()));
        String status = ledger.get("status");
        if (isFinalStatus(status)) {
            return ClaimDecision.ACK_WITHOUT_EXECUTION;
        }
        if (STATUS_RUNNING.equals(status)) {
            if (!distributedSemaphore.tryAcquire(idempotencyKey(message.requestId()), 1, ownerId, executionLeaseTtl)) {
                return ClaimDecision.LEAVE_PENDING;
            }
            return claimDurably(message, ownerId);
        }
        if (keyValueStore.exists(cancelKey(message.requestId()))) {
            markLedger(message, STATUS_CANCELLED, ownerId,
                    TriggerExecutionResult.failure(message.runIdPublic(), message.triggerId(), message.triggerType(),
                            "Execution was cancelled before start"));
            return ClaimDecision.ACK_WITHOUT_EXECUTION;
        }

        String idempotencyKey = idempotencyKey(message.requestId());
        if (!distributedSemaphore.tryAcquire(idempotencyKey, 1, ownerId, executionLeaseTtl)) {
            return ClaimDecision.LEAVE_PENDING;
        }

        ledger = keyValueStore.hashGetAll(ledgerKey(message.requestId()));
        status = ledger.get("status");
        if (isFinalStatus(status) || STATUS_RUNNING.equals(status)) {
            distributedSemaphore.release(idempotencyKey, ownerId);
            return isFinalStatus(status) ? ClaimDecision.ACK_WITHOUT_EXECUTION : ClaimDecision.LEAVE_PENDING;
        }

        return claimDurably(message, ownerId);
    }

    private ClaimDecision claimDurably(QueuedExecutionMessage message, String ownerId) {
        ExecutionQueueClaimStore.ClaimRecord durableClaim = claimStore.claimForExecution(message, ownerId);
        if (durableClaim.isFinal()) {
            try {
                TriggerExecutionResult durableResult = durableClaim.result() != null
                        ? durableClaim.result()
                        : resultFromDurableFinalStatus(message, durableClaim);
                writeResultIfNeeded(message, durableResult);
                markLedger(message, durableClaim.status(), ownerId, durableResult);
                return ClaimDecision.ACK_WITHOUT_EXECUTION;
            } finally {
                distributedSemaphore.release(idempotencyKey(message.requestId()), ownerId);
            }
        }
        if (durableClaim.hasStartedExecutionBoundary()) {
            try {
                markLedger(message, STATUS_RUNNING, ownerId, null);
                return ClaimDecision.ACK_WITHOUT_EXECUTION;
            } finally {
                distributedSemaphore.release(idempotencyKey(message.requestId()), ownerId);
            }
        }
        markLedger(message, STATUS_RUNNING, ownerId, null);
        return ClaimDecision.CLAIMED;
    }

    private TriggerExecutionResult executeRehydrated(QueuedExecutionMessage message) {
        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(message.runIdPublic());
        if (runOpt.isEmpty()) {
            return TriggerExecutionResult.failure(
                    message.runIdPublic(), message.triggerId(), message.triggerType(),
                    "Workflow run no longer exists");
        }

        WorkflowRunEntity run = runOpt.get();
        if (!sameScopeValue(message.tenantId(), run.getTenantId())) {
            return TriggerExecutionResult.failure(
                    message.runIdPublic(), message.triggerId(), message.triggerType(),
                    "Queued execution tenant does not match the workflow run");
        }
        // Org integrity check via the canonical cross-resource workspace match
        // (single source of truth for "same org" - see ScopeGuard.crossResourceMatches).
        if (!ScopeGuard.crossResourceMatches(message.organizationId(), run.getOrganizationId())) {
            return TriggerExecutionResult.failure(
                    message.runIdPublic(), message.triggerId(), message.triggerType(),
                    "Queued execution organization does not match the workflow run");
        }

        TriggerExecutionResult[] result = new TriggerExecutionResult[1];
        TenantResolver.runWithOrgScope(run.getOrganizationId(), run.getOrganizationRole(), () ->
                result[0] = ExecutionQueueRequestContext.runWith(message.requestId(), () ->
                        triggerService.executeTriggerInternal(
                                run, message.triggerId(), message.triggerType(), message.payload(), false)));
        return result[0] != null ? result[0] : TriggerExecutionResult.failure(
                message.runIdPublic(), message.triggerId(), message.triggerType(), "Execution returned no result");
    }

    private boolean completeExecution(
            QueuedExecutionMessage message,
            String status,
            String ownerId,
            TriggerExecutionResult result) {
        try {
            claimStore.complete(message, status, result);
            writeResultIfNeeded(message, result);
            markLedger(message, status, ownerId, result);
            if (message.fireAndForget()) {
                metrics.recordCompleted(message.userPlan(), message.tenantId(), outcomeOf(result));
            }
            return true;
        } catch (Exception e) {
            logger.error("[RedisExecutionQueue] Failed to complete durable claim requestId={}: {}",
                    message.requestId(), e.getMessage(), e);
            return false;
        } finally {
            distributedSemaphore.release(idempotencyKey(message.requestId()), ownerId);
        }
    }

    private void writeResultIfNeeded(QueuedExecutionMessage message, TriggerExecutionResult result) {
        if (message.fireAndForget() || result == null) {
            return;
        }
        try {
            keyValueStore.set(resultKey(message.requestId()), objectMapper.writeValueAsString(result), resultTtl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize execution result", e);
        }
    }

    private void safeWriteResultIfNeeded(QueuedExecutionMessage message, TriggerExecutionResult result) {
        try {
            writeResultIfNeeded(message, result);
        } catch (Exception e) {
            logger.warn("[RedisExecutionQueue] Failed to write result for requestId={}: {}",
                    message.requestId(), e.getMessage());
        }
    }

    private void markLedger(
            QueuedExecutionMessage message,
            String status,
            String ownerId,
            TriggerExecutionResult result) {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", status);
        fields.put("requestId", message.requestId());
        fields.put("runIdPublic", nullToEmpty(message.runIdPublic()));
        fields.put("triggerId", nullToEmpty(message.triggerId()));
        fields.put("tenantId", nullToEmpty(message.tenantId()));
        fields.put("organizationId", nullToEmpty(message.organizationId()));
        fields.put("fireAndForget", String.valueOf(message.fireAndForget()));
        fields.put("updatedAt", Instant.now(clock).toString());
        if (ownerId != null) {
            fields.put("ownerId", ownerId);
            fields.put("leaseUntil", Instant.now(clock).plus(executionLeaseTtl).toString());
        }
        if (result != null) {
            fields.put("success", String.valueOf(result.success()));
            fields.put("message", nullToEmpty(result.message()));
        }
        keyValueStore.hashPutAll(ledgerKey(message.requestId()), fields, ledgerTtl);
    }

    private void safeMarkLedger(
            QueuedExecutionMessage message,
            String status,
            String ownerId,
            TriggerExecutionResult result) {
        try {
            markLedger(message, status, ownerId, result);
        } catch (Exception e) {
            logger.warn("[RedisExecutionQueue] Failed to mark ledger requestId={} status={}: {}",
                    message.requestId(), status, e.getMessage());
        }
    }

    @Override
    public int getQueueSize() {
        long size = queue.size();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    @Override
    public int getAvailableWorkers() {
        if (!isReadyForEnqueue()) {
            return 0;
        }
        int localAvailable = Math.max(0, workerThreads - activeExecutions.get());
        int distributedAvailable = distributedSemaphore.availablePermits(WORKER_SEMAPHORE_KEY, globalWorkerPermits);
        return Math.min(localAvailable, distributedAvailable);
    }

    @Override
    public boolean isReadyForEnqueue() {
        if (!queue.isAvailable()) {
            return false;
        }
        try {
            keyValueStore.exists(namespace + ":health");
            return true;
        } catch (Exception e) {
            logger.warn("[RedisExecutionQueue] Result store availability check failed: {}", e.getMessage());
            return false;
        }
    }

    private void heartbeatWorkerPermits() {
        for (String ownerId : activeWorkerPermits.keySet()) {
            boolean ok = distributedSemaphore.heartbeat(WORKER_SEMAPHORE_KEY, ownerId, workerPermitTtl);
            if (!ok) {
                logger.warn("[RedisExecutionQueue] Worker permit heartbeat failed for ownerId={}", ownerId);
            }
        }
        activeExecutionLeases.forEach((ownerId, message) -> {
            boolean ok = distributedSemaphore.heartbeat(idempotencyKey(message.requestId()), ownerId, executionLeaseTtl);
            if (!ok) {
                logger.warn("[RedisExecutionQueue] Execution lease heartbeat failed for requestId={} ownerId={}",
                        message.requestId(), ownerId);
            }
        });
    }

    @Override
    public void destroy() {
        running.set(false);
        heartbeatPool.shutdownNow();
        workerPool.shutdownNow();
        try {
            workerPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (String ownerId : activeWorkerPermits.keySet()) {
            distributedSemaphore.release(WORKER_SEMAPHORE_KEY, ownerId);
        }
        activeWorkerPermits.clear();
        activeExecutionLeases.clear();
        logger.info("[RedisExecutionQueue] Shut down");
    }

    private String ledgerKey(String requestId) {
        return namespace + ":ledger:" + requestId;
    }

    private String resultKey(String requestId) {
        return namespace + ":result:" + requestId;
    }

    private String cancelKey(String requestId) {
        return namespace + ":cancel:" + requestId;
    }

    private String idempotencyKey(String requestId) {
        return IDEMPOTENCY_SEMAPHORE_PREFIX + requestId;
    }

    private static String outcomeOf(TriggerExecutionResult result) {
        if (result == null) return ExecutionQueueMetrics.OUTCOME_FAILURE;
        return result.success() ? ExecutionQueueMetrics.OUTCOME_SUCCESS : ExecutionQueueMetrics.OUTCOME_FAILURE;
    }

    private static boolean isFinalStatus(String status) {
        return STATUS_DONE.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
    }

    private static TriggerExecutionResult resultFromDurableFinalStatus(
            QueuedExecutionMessage message,
            ExecutionQueueClaimStore.ClaimRecord durableClaim) {
        if (STATUS_DONE.equals(durableClaim.status())) {
            return TriggerExecutionResult.success(
                    message.runIdPublic(), message.triggerId(), message.triggerType(), Set.of(), -1);
        }
        String failureMessage = durableClaim.message() != null && !durableClaim.message().isBlank()
                ? durableClaim.message()
                : "Execution already finished with status " + durableClaim.status();
        return TriggerExecutionResult.failure(
                message.runIdPublic(), message.triggerId(), message.triggerType(), failureMessage);
    }

    private static boolean sameScopeValue(String expected, String actual) {
        String left = expected == null || expected.isBlank() ? null : expected;
        String right = actual == null || actual.isBlank() ? null : actual;
        return Objects.equals(left, right);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static String stableOrRandomRequestId(String requestId) {
        return requestId != null && !requestId.isBlank() ? requestId : UUID.randomUUID().toString();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum ClaimDecision {
        CLAIMED,
        ACK_WITHOUT_EXECUTION,
        LEAVE_PENDING
    }
}
